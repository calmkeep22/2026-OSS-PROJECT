package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.broker.BrokerTransientException;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.KiwoomJsonMapper;
import org.ossproject.kiwoom.KiwoomProperties;

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

class KiwoomMarketDataStreamTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-08T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final URI WS_URI = URI.create("wss://api.example.test/ws");

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

    private FakeConnector connector;
    private ImmediateScheduler scheduler;
    private KiwoomMarketDataStream stream;
    private List<Quote> quotes;
    private List<ConnectionState> states;

    @BeforeEach
    void setUp() {
        KiwoomProperties properties = KiwoomProperties.placeholder(
                URI.create("https://api.example.test"), WS_URI);
        StreamProtocol protocol = new JsonStreamProtocol(
                new KiwoomJsonMapper(new ObjectMapper(), properties, CLOCK), properties);

        connector = new FakeConnector();
        scheduler = new ImmediateScheduler();
        stream = new KiwoomMarketDataStream(WS_URI, connector, protocol, scheduler,
                new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0, 0.0));

        quotes = new ArrayList<>();
        states = new ArrayList<>();
        stream.addQuoteListener(quotes::add);
        stream.addConnectionListener((state, detail) -> states.add(state));
    }

    @Test
    @DisplayName("연결하면 상태가 연결 중을 거쳐 연결됨으로 바뀐다")
    void connects() {
        stream.connect();

        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
        assertEquals(List.of(ConnectionState.CONNECTING, ConnectionState.CONNECTED), states);
    }

    @Test
    @DisplayName("연결 전에 구독한 종목은 연결 후에 한꺼번에 전송된다")
    void sendsPendingSubscriptionsOnConnect() {
        stream.subscribe(List.of("005930", "000660"));
        stream.connect();

        assertEquals(Set.of("005930", "000660"), stream.subscriptions());
        assertEquals(1, connector.lastSession().sent.size());
        String message = connector.lastSession().sent.get(0);
        assertTrue(message.contains("005930"));
        assertTrue(message.contains("000660"));
    }

    @Test
    @DisplayName("연결 중 추가 구독은 즉시 전송된다")
    void sendsSubscriptionWhileConnected() {
        stream.connect();
        stream.subscribe(List.of("005930"));

        assertEquals(1, connector.lastSession().sent.size());
        assertTrue(connector.lastSession().sent.get(0).contains("subscribe"));
    }

    @Test
    @DisplayName("이미 구독 중인 종목은 다시 보내지 않는다")
    void skipsDuplicateSubscription() {
        stream.connect();
        stream.subscribe(List.of("005930"));
        stream.subscribe(List.of("005930"));

        assertEquals(1, connector.lastSession().sent.size());
    }

    @Test
    @DisplayName("시세 메시지를 리스너에 전달한다")
    void deliversQuotes() {
        stream.connect();

        connector.lastHandler.onMessage(
                "{\"symbol\":\"005930\",\"price\":\"73500\",\"volume\":\"18450230\"}");

        assertEquals(1, quotes.size());
        assertEquals("005930", quotes.get(0).symbol());
        assertEquals(0, new BigDecimal("73500").compareTo(quotes.get(0).price()));
    }

    @Test
    @DisplayName("시세가 아닌 메시지는 무시하고 연결을 유지한다")
    void ignoresNonQuoteMessages() {
        stream.connect();

        connector.lastHandler.onMessage("{\"type\":\"subscribed\",\"result\":\"ok\"}");
        connector.lastHandler.onMessage("pong");
        connector.lastHandler.onMessage("깨진 JSON {{{");

        assertTrue(quotes.isEmpty());
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
    }

    @Test
    @DisplayName("연결이 끊기면 재연결하고 구독을 복원한다")
    void reconnectsAndRestoresSubscriptions() {
        stream.subscribe(List.of("005930", "000660"));
        stream.connect();
        assertEquals(1, connector.attempts);

        connector.lastHandler.onClose(1006, "abnormal closure");

        assertEquals(2, connector.attempts);
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
        // 새 세션이 구독을 다시 보냈다.
        String restored = connector.lastSession().sent.get(0);
        assertTrue(restored.contains("005930"));
        assertTrue(restored.contains("000660"));
        assertTrue(states.contains(ConnectionState.RECONNECTING));
    }

    @Test
    @DisplayName("재연결 대기 시간이 지수적으로 늘어난다")
    void backsOffExponentially() {
        connector.failuresRemaining = 3;
        stream.connect();

        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400)),
                scheduler.delays);
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
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
        stream.connect();
        connector.lastSession().sendFails = true;

        stream.subscribe(List.of("005930"));

        assertEquals(2, connector.attempts);
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());
    }

    @Test
    @DisplayName("사용자가 닫으면 재연결하지 않는다")
    void doesNotReconnectAfterClose() {
        stream.connect();
        stream.close();

        int attemptsAfterClose = connector.attempts;
        connector.lastHandler.onClose(1006, "abnormal closure");

        assertEquals(attemptsAfterClose, connector.attempts);
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());
        assertThrows(IllegalStateException.class, stream::connect);
    }

    @Test
    @DisplayName("구독을 해제하면 목록에서 빠지고 해제 메시지를 보낸다")
    void unsubscribes() {
        stream.connect();
        stream.subscribe(List.of("005930", "000660"));
        stream.unsubscribe(List.of("005930"));

        assertEquals(Set.of("000660"), stream.subscriptions());
        List<String> sent = connector.lastSession().sent;
        assertTrue(sent.get(sent.size() - 1).contains("unsubscribe"));
    }

    @Test
    @DisplayName("리스너가 예외를 던져도 다른 리스너는 계속 받는다")
    void isolatesFailingListener() {
        List<Quote> received = new ArrayList<>();
        stream.addQuoteListener(quote -> {
            throw new IllegalStateException("화면 갱신 실패");
        });
        stream.addQuoteListener(received::add);
        stream.connect();

        connector.lastHandler.onMessage("{\"symbol\":\"005930\",\"price\":\"73500\"}");

        assertEquals(1, received.size());
        assertFalse(quotes.isEmpty());
    }
}
