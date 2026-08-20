package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.OrderBookListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.KiwoomProperties;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 키움 실시간 시세 스트림.
 *
 * <p>연결이 끊기면 지수 백오프로 재연결하고, 재연결에 성공하면 끊기기 전에 구독하던 종목을
 * 자동으로 다시 구독한다. 사용자가 직접 종목을 다시 등록하게 만들면, 화면을 눈으로 확인할 수
 * 없는 사용자에게는 복구가 사실상 불가능하다.
 *
 * <p>연결 상태 변화는 {@link ConnectionListener} 로 알린다. 화면 계층은 이 신호를 상태음과
 * 음성 안내로 옮긴다.
 */
public final class KiwoomMarketDataStream implements MarketDataStreamPort {

    /** 종목을 구독할 때 함께 등록하는 실시간 종류. 지금은 호가잔량만 쓴다. */
    private static final List<KiwoomRealtimeType> SUBSCRIBED_TYPES =
            List.of(KiwoomRealtimeType.TRADE, KiwoomRealtimeType.ORDER_BOOK);

    /**
     * 로그인 응답을 기다리는 시한.
     *
     * <p>소켓이 열려도 로그인 응답을 받기 전에는 아무것도 할 수 없다. 서버가 응답 없이
     * 붙잡고 있으면 {@code onClose} 도 {@code onError} 도 오지 않아 연결 중 상태에 그대로
     * 머문다. 시한을 두지 않으면 사용자가 할 수 있는 일이 앱 재시작밖에 없다.
     */
    private static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(10);


    private final URI uri;
    private final WebSocketConnector connector;
    private final KiwoomWebSocketProtocol protocol;
    private final ReconnectScheduler scheduler;
    /** 로그인 응답을 기다리는 시한을 재는 예약기. 재연결 예약과 섞이지 않게 따로 둔다. */
    private final ReconnectScheduler loginWatchdog;
    private final Duration loginTimeout;
    private final RetryPolicy reconnectPolicy;

    /** 재연결 때마다 새 토큰을 받아야 하므로 값이 아니라 공급자를 들고 있는다. */
    private final Supplier<String> accessTokenSupplier;

    private final List<QuoteListener> quoteListeners = new CopyOnWriteArrayList<>();
    private final List<OrderBookListener> orderBookListeners = new CopyOnWriteArrayList<>();
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private final Set<String> subscriptions = new LinkedHashSet<>();

    private WebSocketSession session;
    private ConnectionState state = ConnectionState.DISCONNECTED;
    private int reconnectAttempt;
    private boolean closedByUser;

    /**
     * 실제 접속용 스트림을 만든다.
     *
     * @param accessTokenSupplier 로그인 패킷에 넣을 접근 토큰 공급자.
     *                            재연결할 때마다 다시 호출되므로 만료된 토큰이 재사용되지 않는다
     */
    public KiwoomMarketDataStream(KiwoomProperties properties, Supplier<String> accessTokenSupplier) {
        this(properties.webSocketUrl(),
                new JdkWebSocketConnector(),
                new KiwoomWebSocketProtocol(new ObjectMapper(), Clock.systemDefaultZone()),
                ReconnectScheduler.daemon(),
                RetryPolicy.defaults(),
                accessTokenSupplier);
    }

    KiwoomMarketDataStream(URI uri, WebSocketConnector connector, KiwoomWebSocketProtocol protocol,
                           ReconnectScheduler scheduler, RetryPolicy reconnectPolicy,
                           Supplier<String> accessTokenSupplier) {
        this(uri, connector, protocol, scheduler, ReconnectScheduler.daemon(),
                DEFAULT_LOGIN_TIMEOUT, reconnectPolicy, accessTokenSupplier);
    }

    KiwoomMarketDataStream(URI uri, WebSocketConnector connector, KiwoomWebSocketProtocol protocol,
                           ReconnectScheduler scheduler, ReconnectScheduler loginWatchdog,
                           Duration loginTimeout, RetryPolicy reconnectPolicy,
                           Supplier<String> accessTokenSupplier) {
        if (loginWatchdog == null) {
            throw new IllegalArgumentException("로그인 감시 예약기는 필수입니다.");
        }
        if (loginTimeout == null || loginTimeout.isNegative() || loginTimeout.isZero()) {
            throw new IllegalArgumentException("로그인 응답 시한은 0보다 커야 합니다.");
        }
        this.loginWatchdog = loginWatchdog;
        this.loginTimeout = loginTimeout;
        if (uri == null) {
            throw new IllegalArgumentException("스트림 주소는 필수입니다.");
        }
        if (connector == null) {
            throw new IllegalArgumentException("연결 생성기는 필수입니다.");
        }
        if (protocol == null) {
            throw new IllegalArgumentException("프로토콜은 필수입니다.");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("재연결 스케줄러는 필수입니다.");
        }
        if (reconnectPolicy == null) {
            throw new IllegalArgumentException("재연결 정책은 필수입니다.");
        }
        this.uri = uri;
        this.connector = connector;
        this.protocol = protocol;
        if (accessTokenSupplier == null) {
            throw new IllegalArgumentException("접근 토큰 공급자는 필수입니다.");
        }
        this.scheduler = scheduler;
        this.reconnectPolicy = reconnectPolicy;
        this.accessTokenSupplier = accessTokenSupplier;
    }

    // ------------------------------------------------------------------
    // 연결
    // ------------------------------------------------------------------

    @Override
    public void connect() {
        List<Runnable> events = new ArrayList<>();
        synchronized (lock) {
            if (closedByUser) {
                throw new IllegalStateException("이미 닫힌 스트림은 다시 열 수 없습니다.");
            }
            if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
                return;
            }
            reconnectAttempt = 0;
            changeState(ConnectionState.CONNECTING, null, events);
        }
        runEvents(events);
        openConnection();
    }

    /** 연결을 시도하고 결과에 따라 상태를 바꾼다. 실패하면 재연결을 예약한다. */
    private void openConnection() {
        WebSocketSession opened;
        try {
            opened = connector.connect(uri, new Handler());
        } catch (RuntimeException e) {
            handleDisconnect("연결에 실패했습니다. " + e.getMessage());
            return;
        }

        synchronized (lock) {
            if (closedByUser) {
                opened.close();
                return;
            }
            session = opened;
        }

        // 연결만으로는 아무것도 받을 수 없다. 로그인에 성공해야 구독을 보낼 수 있으므로,
        // CONNECTED 로 표시하는 것도 로그인 응답을 받은 뒤로 미룬다.
        String token;
        try {
            token = accessTokenSupplier.get();
        } catch (RuntimeException e) {
            handleDisconnect("접근 토큰을 발급받지 못했습니다. " + e.getMessage());
            return;
        }
        sendQuietly(protocol.loginMessage(token));
        watchLoginResponse(opened);
    }

    /**
     * 로그인 응답이 시한 안에 오지 않으면 끊고 재연결한다.
     *
     * <p>{@code expected} 와 지금 세션이 다르면 이미 다른 연결로 넘어간 것이므로 아무것도
     * 하지 않는다. 로그인에 성공했으면 상태가 연결됨으로 바뀌어 있어 역시 지나간다.
     */
    private void watchLoginResponse(WebSocketSession expected) {
        loginWatchdog.schedule(loginTimeout, () -> {
            boolean stillWaiting;
            synchronized (lock) {
                stillWaiting = !closedByUser && session == expected
                        && state != ConnectionState.CONNECTED;
            }
            if (stillWaiting) {
                handleDisconnect("로그인 응답이 " + loginTimeout.toMillis() + "밀리초 안에 오지 않았습니다.");
            }
        });
    }

    /** 로그인에 성공하면 연결됨으로 표시하고, 끊기기 전에 보던 종목을 다시 등록한다. */
    private void onLoginSucceeded() {
        List<Runnable> events = new ArrayList<>();
        Set<String> toResubscribe;
        synchronized (lock) {
            if (closedByUser) {
                return;
            }
            reconnectAttempt = 0;
            changeState(ConnectionState.CONNECTED, null, events);
            toResubscribe = new LinkedHashSet<>(subscriptions);
        }
        runEvents(events);

        if (!toResubscribe.isEmpty()) {
            // 재연결이라면 서버 쪽 등록이 이미 사라졌으므로 대체 등록으로 보낸다.
            sendQuietly(protocol.registerMessage(toResubscribe, SUBSCRIBED_TYPES, false));
        }
    }

    /**
     * 끊김을 처리하고 다음 재연결을 예약한다.
     *
     * <p>참조만 버리지 않고 소켓을 실제로 닫는다. 토큰 발급 실패처럼 소켓이 열린 채로
     * 들어오는 경로가 있어서, 참조만 지우면 재연결할 때마다 열린 소켓이 하나씩 쌓인다.
     * {@code onClose} 로 들어온 경우처럼 이미 닫혀 있어도 다시 닫는 것은 문제가 없다.
     */
    private void handleDisconnect(String reason) {
        List<Runnable> events = new ArrayList<>();
        WebSocketSession stale;
        Duration delay;
        boolean giveUp;

        synchronized (lock) {
            if (closedByUser) {
                return;
            }
            stale = session;
            session = null;
            reconnectAttempt++;

            giveUp = reconnectPolicy.maxAttempts() > 0
                    && reconnectAttempt > reconnectPolicy.maxAttempts();
            if (giveUp) {
                changeState(ConnectionState.FAILED,
                        "재연결을 " + reconnectPolicy.maxAttempts() + "회 시도했지만 실패했습니다. " + reason,
                        events);
                delay = null;
            } else {
                delay = reconnectPolicy.delayAfterAttempt(reconnectAttempt);
                changeState(ConnectionState.RECONNECTING,
                        reason + " " + delay.toMillis() + "밀리초 뒤에 다시 시도합니다.", events);
            }
        }

        closeQuietly(stale);
        runEvents(events);
        if (!giveUp) {
            scheduler.schedule(delay, this::openConnection);
        }
    }

    /** 종료 중 오류는 알릴 대상이 아니다. 이미 닫힌 소켓을 다시 닫아도 조용히 넘어간다. */
    private static void closeQuietly(WebSocketSession target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (RuntimeException ignored) {
            // 닫는 중 오류로 재연결을 막지 않는다.
        }
    }

    // ------------------------------------------------------------------
    // 구독
    // ------------------------------------------------------------------

    @Override
    public void subscribe(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        Set<String> added = new LinkedHashSet<>();
        boolean connected;
        synchronized (lock) {
            for (String symbol : symbols) {
                if (symbol != null && !symbol.isBlank() && subscriptions.add(symbol)) {
                    added.add(symbol);
                }
            }
            connected = state == ConnectionState.CONNECTED && session != null;
        }
        if (connected && !added.isEmpty()) {
            sendQuietly(protocol.registerMessage(added, SUBSCRIBED_TYPES, true));
        }
    }

    @Override
    public void unsubscribe(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        Set<String> removed = new LinkedHashSet<>();
        boolean connected;
        synchronized (lock) {
            for (String symbol : symbols) {
                if (subscriptions.remove(symbol)) {
                    removed.add(symbol);
                }
            }
            connected = state == ConnectionState.CONNECTED && session != null;
        }
        if (connected && !removed.isEmpty()) {
            sendQuietly(protocol.removeMessage(removed, SUBSCRIBED_TYPES));
        }
    }

    @Override
    public Set<String> subscriptions() {
        synchronized (lock) {
            return Set.copyOf(subscriptions);
        }
    }

    /** 전송 실패는 곧 끊김으로 이어지므로 재연결 흐름에 맡긴다. */
    private void sendQuietly(String message) {
        WebSocketSession current;
        synchronized (lock) {
            current = session;
        }
        if (current == null) {
            return;
        }
        try {
            current.send(message);
        } catch (RuntimeException e) {
            handleDisconnect("메시지를 보내지 못했습니다. " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 리스너
    // ------------------------------------------------------------------

    @Override
    public void addQuoteListener(QuoteListener listener) {
        if (listener != null) {
            quoteListeners.add(listener);
        }
    }

    @Override
    public void removeQuoteListener(QuoteListener listener) {
        quoteListeners.remove(listener);
    }

    @Override
    public boolean supportsOrderBook() {
        return true;
    }

    @Override
    public void addOrderBookListener(OrderBookListener listener) {
        if (listener != null) {
            orderBookListeners.add(listener);
        }
    }

    @Override
    public void removeOrderBookListener(OrderBookListener listener) {
        orderBookListeners.remove(listener);
    }

    @Override
    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    @Override
    public ConnectionState connectionState() {
        synchronized (lock) {
            return state;
        }
    }

    @Override
    public void close() {
        WebSocketSession current;
        List<Runnable> events = new ArrayList<>();
        synchronized (lock) {
            if (closedByUser) {
                return;
            }
            closedByUser = true;
            current = session;
            session = null;
            changeState(ConnectionState.DISCONNECTED, "사용자가 연결을 종료했습니다.", events);
        }
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
                // 종료 중 오류는 알릴 대상이 아니다.
            }
        }
        scheduler.shutdown();
        loginWatchdog.shutdown();
        runEvents(events);
    }

    /** 락을 잡은 상태에서 호출한다. 실제 통지는 락을 놓은 뒤 {@link #runEvents} 가 한다. */
    private void changeState(ConnectionState next, String detail, List<Runnable> events) {
        if (state == next) {
            return;
        }
        state = next;
        events.add(() -> notifyConnection(next, detail));
    }

    private void runEvents(List<Runnable> events) {
        for (Runnable event : events) {
            event.run();
        }
    }

    private void notifyConnection(ConnectionState newState, String detail) {
        for (ConnectionListener listener : connectionListeners) {
            try {
                listener.onConnectionStateChanged(newState, detail);
            } catch (RuntimeException ignored) {
                // 한 리스너의 실패가 다른 리스너를 막지 않는다.
            }
        }
    }

    private void notifyOrderBook(OrderBook orderBook) {
        for (OrderBookListener listener : orderBookListeners) {
            try {
                listener.onOrderBook(orderBook);
            } catch (RuntimeException ignored) {
                // 한 리스너의 실패가 다른 리스너를 막지 않는다.
            }
        }
    }

    private void notifyQuote(Quote quote) {
        for (QuoteListener listener : quoteListeners) {
            try {
                listener.onQuote(quote);
            } catch (RuntimeException ignored) {
                // 위와 같다.
            }
        }
    }

    /** WebSocket 사건을 스트림 상태 기계로 넘긴다. */
    private final class Handler implements WebSocketHandler {

        @Override
        public void onMessage(String message) {
            for (KiwoomStreamEvent event : protocol.decode(message)) {
                dispatch(event);
            }
        }

        private void dispatch(KiwoomStreamEvent event) {
            if (event instanceof KiwoomStreamEvent.Ping ping) {
                // 응답하지 않으면 서버가 연결을 끊는다.
                sendQuietly(ping.echo());
            } else if (event instanceof KiwoomStreamEvent.LoginResult login) {
                if (login.success()) {
                    onLoginSucceeded();
                } else {
                    handleDisconnect("실시간 로그인에 실패했습니다. " + login.message());
                }
            } else if (event instanceof KiwoomStreamEvent.OrderBookUpdate update) {
                notifyOrderBook(update.orderBook());
            } else if (event instanceof KiwoomStreamEvent.QuoteUpdate update) {
                notifyQuote(update.quote());
            }
        }

        @Override
        public void onClose(int statusCode, String reason) {
            handleDisconnect("실시간 연결이 끊겼습니다. 코드 " + statusCode
                    + (reason == null || reason.isBlank() ? "" : ", 사유 " + reason) + ".");
        }

        @Override
        public void onError(Throwable error) {
            handleDisconnect("실시간 연결에 오류가 발생했습니다. "
                    + (error == null ? "" : String.valueOf(error.getMessage())));
        }
    }
}
