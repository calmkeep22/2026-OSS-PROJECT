package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.application.contract.MarketDataStreamPortContract;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.broker.BrokerTransientException;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.Quote;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiwoomMarketDataStreamTest extends MarketDataStreamPortContract {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final URI WS_URI = URI.create("wss://mockapi.kiwoom.com:10000/api/dostk/websocket");

    /** 실제 서버가 LOGIN 패킷을 받은 뒤 돌려주는 성공 응답. */
    private static final String LOGIN_OK =
            "{\"trnm\":\"LOGIN\",\"return_code\":0,\"return_msg\":\"정상\"}";

    /** 보낸 메시지를 기록하는 가짜 세션. */
    private static final class FakeSession implements WebSocketSession {
        private final List<String> sent = new ArrayList<>();
        private boolean open = true;
        private boolean sendFails;

        @Override
        public void send(String message) {
            if (sendFails) {
                throw new BrokerTransientException("전송 실패");
            }
            sent.add(message);
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /** 연결 시도를 기록하고, 원하면 실패시킬 수 있는 가짜 연결기. */
    private static final class FakeConnector implements WebSocketConnector {
        private final List<FakeSession> sessions = new ArrayList<>();
        private WebSocketHandler lastHandler;
        private int failuresRemaining;
        private int attempts;

        @Override
        public WebSocketSession connect(URI uri, WebSocketHandler handler) {
            attempts++;
            lastHandler = handler;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new BrokerTransientException("연결 거부됨");
            }
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }

        FakeSession lastSession() {
            return sessions.get(sessions.size() - 1);
        }
    }

    /** 예약된 작업을 바로 실행하고 지연 시간을 기록한다. */
    private static final class ImmediateScheduler implements ReconnectScheduler {
        private final List<Duration> delays = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void schedule(Duration delay, Runnable task) {
            delays.add(delay);
            if (!shutdown) {
                task.run();
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }
    }

    /** 예약만 받아 두고, 테스트가 부를 때 실행한다. 시한이 지난 상황을 흉내 낸다. */
    private static final class ManualScheduler implements ReconnectScheduler {
        private final List<Runnable> pending = new ArrayList<>();
        private boolean shutdown;

        @Override
        public void schedule(Duration delay, Runnable task) {
            if (!shutdown) {
                pending.add(task);
            }
        }

        @Override
        public void shutdown() {
            shutdown = true;
            pending.clear();
        }

        /** 예약된 작업을 모두 실행한다. */
        void fire() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            due.forEach(Runnable::run);
        }
    }

    private FakeConnector connector;
    private ImmediateScheduler scheduler;
    private ManualScheduler watchdog;
    private KiwoomMarketDataStream stream;
    private List<Quote> quotes;
    private List<OrderBook> books;
    private List<ConnectionState> states;
    private java.util.function.Supplier<String> tokenSupplier;

    @BeforeEach
    void setUp() {
        connector = new FakeConnector();
        scheduler = new ImmediateScheduler();
        watchdog = new ManualScheduler();
        tokenSupplier = () -> "test-token";
        stream = new KiwoomMarketDataStream(WS_URI, connector,
                new KiwoomWebSocketProtocol(new ObjectMapper(), CLOCK), scheduler, watchdog,
                Duration.ofSeconds(10),
                new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0, 0.0),
                () -> tokenSupplier.get());

        quotes = new ArrayList<>();
        books = new ArrayList<>();
        states = new ArrayList<>();
        stream.addQuoteListener(quotes::add);
        stream.addOrderBookListener(books::add);
        stream.addConnectionListener((state, detail) -> states.add(state));
    }

    @Override
    protected MarketDataStreamPort createStream() {
        return stream;
    }

    /** 연결하고 로그인까지 마쳐 실제로 구독 가능한 상태로 만든다. */
    private void connectAndLogin() {
        stream.connect();
        connector.lastHandler.onMessage(LOGIN_OK);
    }

    @Test
    @DisplayName("연결하면 먼저 로그인 패킷을 보낸다")
    void sendsLoginOnConnect() {
        stream.connect();

        assertEquals(1, connector.lastSession().sent.size());
        String login = connector.lastSession().sent.get(0);
        assertTrue(login.contains("\"trnm\":\"LOGIN\""));
        assertTrue(login.contains("test-token"));
    }

    @Test
    @DisplayName("로그인 응답을 받기 전에는 연결됨으로 보지 않는다")
    void staysConnectingUntilLoginSucceeds() {
        stream.connect();

        assertEquals(ConnectionState.CONNECTING, stream.connectionState());

        connector.lastHandler.onMessage(LOGIN_OK);

        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
        assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTED), states);
    }

    @Test
    @DisplayName("로그인에 실패하면 재연결 흐름으로 넘어간다")
    void reconnectsWhenLoginFails() {
        stream.connect();

        connector.lastHandler.onMessage(
                "{\"trnm\":\"LOGIN\",\"return_code\":3,\"return_msg\":\"토큰 오류\"}");

        assertTrue(states.contains(ConnectionState.RECONNECTING));
        assertTrue(connector.attempts >= 2);
    }

    @Test
    @DisplayName("연결 전에 구독한 종목은 로그인 성공 후 한꺼번에 등록된다")
    void registersPendingSubscriptionsAfterLogin() {
        stream.subscribe(List.of("005930", "000660"));

        connectAndLogin();

        assertEquals(Set.of("005930", "000660"), stream.subscriptions());
        String reg = connector.lastSession().sent.get(1);
        assertTrue(reg.contains("\"trnm\":\"REG\""));
        assertTrue(reg.contains("005930"));
        assertTrue(reg.contains("000660"));
        assertTrue(reg.contains("\"0D\""));
    }

    @Test
    @DisplayName("연결 중 추가 구독은 기존 등록을 유지한 채 보낸다")
    void addsSubscriptionWhileConnected() {
        connectAndLogin();

        stream.subscribe(List.of("005930"));

        String reg = connector.lastSession().sent.get(1);
        assertTrue(reg.contains("\"trnm\":\"REG\""));
        assertTrue(reg.contains("\"refresh\":\"1\""));
    }

    @Test
    @DisplayName("이미 구독 중인 종목은 다시 보내지 않는다")
    void skipsDuplicateSubscription() {
        connectAndLogin();

        stream.subscribe(List.of("005930"));
        stream.subscribe(List.of("005930"));

        // 로그인 패킷 + 등록 패킷 하나
        assertEquals(2, connector.lastSession().sent.size());
    }

    @Test
    @DisplayName("PING 을 받으면 같은 패킷을 되돌려보낸다")
    void echoesPing() {
        connectAndLogin();
        int before = connector.lastSession().sent.size();

        connector.lastHandler.onMessage("{\"trnm\":\"PING\",\"seq\":\"7\"}");

        List<String> sent = connector.lastSession().sent;
        assertEquals(before + 1, sent.size());
        assertEquals("{\"trnm\":\"PING\",\"seq\":\"7\"}", sent.get(sent.size() - 1));
    }

    @Test
    @DisplayName("실시간 호가창을 리스너에 전달한다")
    void deliversOrderBook() {
        connectAndLogin();

        connector.lastHandler.onMessage("""
                {"trnm":"REAL","data":[{"type":"0D","item":"005930",
                 "values":{"41":"-73500","61":"180","51":"73400","71":"310"}}]}""");

        assertEquals(1, books.size());
        assertEquals("005930", books.get(0).symbol());
        assertEquals(0, new BigDecimal("73500").compareTo(books.get(0).bestAsk().orElseThrow()));
    }

    @Test
    @DisplayName("실시간 체결을 현재가로 전달한다")
    void deliversQuote() {
        connectAndLogin();

        connector.lastHandler.onMessage("""
                {"trnm":"REAL","data":[{"type":"0B","item":"005930",
                 "values":{"10":"-73500","13":"18450230","27":"73600","28":"73400"}}]}""");

        assertEquals(1, quotes.size());
        assertEquals(0, new BigDecimal("73500").compareTo(quotes.get(0).price()));
        assertEquals(18_450_230L, quotes.get(0).cumulativeVolume());
    }

    @Test
    @DisplayName("호가 지원 여부를 알린다")
    void reportsOrderBookSupport() {
        assertTrue(stream.supportsOrderBook());
    }

    @Test
    @DisplayName("알 수 없는 메시지는 무시하고 연결을 유지한다")
    void ignoresUnknownMessages() {
        connectAndLogin();

        connector.lastHandler.onMessage("{\"trnm\":\"REG\",\"return_code\":0}");
        connector.lastHandler.onMessage("깨진 JSON {{{");

        assertTrue(books.isEmpty());
        assertTrue(quotes.isEmpty());
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
    }

    @Test
    @DisplayName("연결이 끊기면 재연결하고 구독을 대체 등록으로 복원한다")
    void reconnectsAndRestoresSubscriptions() {
        stream.subscribe(List.of("005930", "000660"));
        connectAndLogin();
        assertEquals(1, connector.attempts);

        connector.lastHandler.onClose(1006, "abnormal closure");
        connector.lastHandler.onMessage(LOGIN_OK);

        assertEquals(2, connector.attempts);
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());

        String reg = connector.lastSession().sent.get(1);
        assertTrue(reg.contains("005930"));
        assertTrue(reg.contains("000660"));
        // 서버 쪽 등록이 사라졌으므로 유지가 아니라 대체로 보내야 한다.
        assertTrue(reg.contains("\"refresh\":\"0\""));
        assertTrue(states.contains(ConnectionState.RECONNECTING));
    }

    @Test
    @DisplayName("재연결마다 토큰을 다시 받아 만료된 토큰을 쓰지 않는다")
    void refreshesTokenOnReconnect() {
        List<String> issued = new ArrayList<>();
        KiwoomMarketDataStream refreshing = new KiwoomMarketDataStream(WS_URI, connector,
                new KiwoomWebSocketProtocol(new ObjectMapper(), CLOCK), scheduler,
                new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0, 0.0),
                () -> {
                    String token = "token-" + (issued.size() + 1);
                    issued.add(token);
                    return token;
                });

        refreshing.connect();
        connector.lastHandler.onClose(1006, "abnormal closure");

        assertEquals(2, issued.size());
        assertTrue(connector.lastSession().sent.get(0).contains("token-2"));
    }

    @Test
    @DisplayName("재연결 대기 시간이 지수적으로 늘어난다")
    void backsOffExponentially() {
        connector.failuresRemaining = 3;
        stream.connect();

        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400)),
                scheduler.delays);
    }

    @Test
    @DisplayName("재연결 횟수를 넘기면 실패 상태로 알린다")
    void givesUpAfterMaxAttempts() {
        connector.failuresRemaining = 99;

        stream.connect();

        assertEquals(ConnectionState.FAILED, stream.connectionState());
        assertEquals(5, scheduler.delays.size());
    }

    @Test
    @DisplayName("전송에 실패하면 재연결 흐름으로 넘어간다")
    void reconnectsWhenSendFails() {
        connectAndLogin();
        connector.lastSession().sendFails = true;

        stream.subscribe(List.of("005930"));

        assertEquals(2, connector.attempts);
    }

    @Test
    @DisplayName("사용자가 닫으면 재연결하지 않는다")
    void doesNotReconnectAfterClose() {
        connectAndLogin();
        stream.close();

        int attemptsAfterClose = connector.attempts;
        connector.lastHandler.onClose(1006, "abnormal closure");

        assertEquals(attemptsAfterClose, connector.attempts);
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());
        assertThrows(IllegalStateException.class, stream::connect);
    }

    @Test
    @DisplayName("구독을 해제하면 목록에서 빠지고 해제 패킷을 보낸다")
    void unsubscribes() {
        connectAndLogin();
        stream.subscribe(List.of("005930", "000660"));
        stream.unsubscribe(List.of("005930"));

        assertEquals(Set.of("000660"), stream.subscriptions());
        List<String> sent = connector.lastSession().sent;
        assertTrue(sent.get(sent.size() - 1).contains("\"trnm\":\"REMOVE\""));
    }

    @Test
    @DisplayName("리스너가 예외를 던져도 다른 리스너는 계속 받는다")
    void isolatesFailingListener() {
        List<OrderBook> received = new ArrayList<>();
        stream.addOrderBookListener(book -> {
            throw new IllegalStateException("화면 갱신 실패");
        });
        stream.addOrderBookListener(received::add);
        connectAndLogin();

        connector.lastHandler.onMessage("""
                {"trnm":"REAL","data":[{"type":"0D","item":"005930",
                 "values":{"41":"73500","61":"180","51":"73400","71":"310"}}]}""");

        assertEquals(1, received.size());
        assertFalse(books.isEmpty());
    }

    // ------------------------------------------------------------------
    // 연결 수명주기
    // ------------------------------------------------------------------

    @Test
    @DisplayName("토큰 발급에 실패하면 이미 열린 소켓을 닫는다")
    void closesTheOpenedSocketWhenTheTokenCannotBeIssued() {
        tokenSupplier = () -> { throw new BrokerTransientException("토큰 발급 실패"); };

        stream.connect();

        assertFalse(connector.sessions.get(0).isOpen(),
                "참조만 버리면 재연결마다 열린 소켓이 쌓입니다");
    }

    @Test
    @DisplayName("재연결할 때마다 이전 소켓을 남기지 않는다")
    void doesNotPileUpSocketsAcrossReconnects() {
        tokenSupplier = () -> { throw new BrokerTransientException("토큰 발급 실패"); };

        stream.connect();

        assertTrue(connector.attempts > 1, "재연결을 시도해야 합니다");
        assertTrue(connector.sessions.stream().noneMatch(FakeSession::isOpen),
                "열린 채 남은 소켓이 없어야 합니다");
    }

    @Test
    @DisplayName("로그인 응답이 오지 않으면 시한 뒤에 끊고 다시 시도한다")
    void reconnectsWhenTheLoginResponseNeverArrives() {
        stream.connect();
        assertEquals(ConnectionState.CONNECTING, stream.connectionState());
        FakeSession stuck = connector.lastSession();
        int attemptsBefore = connector.attempts;

        watchdog.fire();

        assertFalse(stuck.isOpen(), "응답 없는 소켓을 닫아야 합니다");
        assertTrue(connector.attempts > attemptsBefore, "재연결을 시도해야 합니다");
        assertTrue(states.contains(ConnectionState.RECONNECTING));
    }

    @Test
    @DisplayName("로그인에 성공했으면 시한이 지나도 끊지 않는다")
    void leavesAHealthyConnectionAloneWhenTheDeadlinePasses() {
        connectAndLogin();
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
        FakeSession healthy = connector.lastSession();

        watchdog.fire();

        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
        assertTrue(healthy.isOpen());
    }

    @Test
    @DisplayName("사용자가 닫은 뒤에는 시한이 지나도 다시 연결하지 않는다")
    void staysClosedWhenTheDeadlinePassesAfterTheUserClosedIt() {
        stream.connect();
        int attemptsBefore = connector.attempts;

        stream.close();
        watchdog.fire();

        assertEquals(attemptsBefore, connector.attempts);
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());
    }
}
