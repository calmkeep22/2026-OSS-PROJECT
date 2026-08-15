package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.KiwoomJsonMapper;
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

    private final URI uri;
    private final WebSocketConnector connector;
    private final StreamProtocol protocol;
    private final ReconnectScheduler scheduler;
    private final RetryPolicy reconnectPolicy;

    private final List<QuoteListener> quoteListeners = new CopyOnWriteArrayList<>();
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private final Set<String> subscriptions = new LinkedHashSet<>();

    private WebSocketSession session;
    private ConnectionState state = ConnectionState.DISCONNECTED;
    private int reconnectAttempt;
    private boolean closedByUser;

    /** Creates a production stream while keeping transport and protocol details internal. */
    public KiwoomMarketDataStream(KiwoomProperties properties) {
        this(properties.webSocketUrl(),
                new JdkWebSocketConnector(),
                new JsonStreamProtocol(
                        new KiwoomJsonMapper(new ObjectMapper(), properties, Clock.systemDefaultZone()),
                        properties),
                ReconnectScheduler.daemon(),
                RetryPolicy.defaults());
    }

    KiwoomMarketDataStream(URI uri, WebSocketConnector connector, StreamProtocol protocol,
                           ReconnectScheduler scheduler, RetryPolicy reconnectPolicy) {
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
        this.scheduler = scheduler;
        this.reconnectPolicy = reconnectPolicy;
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

        List<Runnable> events = new ArrayList<>();
        Set<String> toResubscribe;
        synchronized (lock) {
            if (closedByUser) {
                opened.close();
                return;
            }
            session = opened;
            reconnectAttempt = 0;
            changeState(ConnectionState.CONNECTED, null, events);
            toResubscribe = new LinkedHashSet<>(subscriptions);
        }
        runEvents(events);

        if (!toResubscribe.isEmpty()) {
            sendQuietly(protocol.subscribeMessage(toResubscribe));
        }
    }

    /** 끊김을 처리하고 다음 재연결을 예약한다. */
    private void handleDisconnect(String reason) {
        List<Runnable> events = new ArrayList<>();
        Duration delay;

        synchronized (lock) {
            if (closedByUser) {
                return;
            }
            session = null;
            reconnectAttempt++;

            if (reconnectPolicy.maxAttempts() > 0 && reconnectAttempt > reconnectPolicy.maxAttempts()) {
                changeState(ConnectionState.FAILED,
                        "재연결을 " + reconnectPolicy.maxAttempts() + "회 시도했지만 실패했습니다. " + reason,
                        events);
                runEvents(events);
                return;
            }

            delay = reconnectPolicy.delayAfterAttempt(reconnectAttempt);
            changeState(ConnectionState.RECONNECTING,
                    reason + " " + delay.toMillis() + "밀리초 뒤에 다시 시도합니다.", events);
        }

        runEvents(events);
        scheduler.schedule(delay, this::openConnection);
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
            sendQuietly(protocol.subscribeMessage(added));
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
            sendQuietly(protocol.unsubscribeMessage(removed));
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
            protocol.parseQuote(message).ifPresent(KiwoomMarketDataStream.this::notifyQuote);
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
