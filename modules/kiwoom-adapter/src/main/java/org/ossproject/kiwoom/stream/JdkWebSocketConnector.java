package org.ossproject.kiwoom.stream;

import org.ossproject.broker.error.BrokerTransientException;
import org.ossproject.broker.auth.SensitiveDataMasker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JDK {@code java.net.http.WebSocket} 기반 연결 생성기.
 *
 * <p>텍스트 프레임은 여러 조각으로 나뉘어 올 수 있으므로 {@code last} 플래그가 설 때까지
 * 모아서 한 번에 전달한다. 이 처리를 빠뜨리면 긴 시세 메시지가 잘려 파싱에 실패한다.
 */
final class JdkWebSocketConnector implements WebSocketConnector {

    private final HttpClient client;
    private final Duration connectTimeout;
    private final Duration sendTimeout;

    public JdkWebSocketConnector() {
        this(HttpClient.newHttpClient(), Duration.ofSeconds(10), Duration.ofSeconds(5));
    }

    public JdkWebSocketConnector(HttpClient client, Duration connectTimeout, Duration sendTimeout) {
        if (client == null) {
            throw new IllegalArgumentException("HTTP 클라이언트는 필수입니다.");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("연결 제한 시간은 0보다 커야 합니다.");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("전송 제한 시간은 0보다 커야 합니다.");
        }
        this.client = client;
        this.connectTimeout = connectTimeout;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public WebSocketSession connect(URI uri, WebSocketHandler handler) {
        if (uri == null) {
            throw new IllegalArgumentException("주소는 필수입니다.");
        }
        if (handler == null) {
            throw new IllegalArgumentException("핸들러는 필수입니다.");
        }
        try {
            WebSocket webSocket = client.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(uri, new AccumulatingListener(handler))
                    .get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new JdkSession(webSocket, sendTimeout);
        } catch (TimeoutException e) {
            throw new BrokerTransientException("실시간 서버 연결이 제한 시간 안에 완료되지 않았습니다.", e);
        } catch (ExecutionException e) {
            throw new BrokerTransientException("실시간 서버에 연결하지 못했습니다. "
                    + SensitiveDataMasker.mask(String.valueOf(e.getCause())), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerTransientException("실시간 서버 연결이 중단되었습니다.", e);
        }
    }

    /** 조각난 텍스트 프레임을 모아 완성된 메시지만 넘긴다. */
    private static final class AccumulatingListener implements WebSocket.Listener {

        private final WebSocketHandler handler;
        private final StringBuilder buffer = new StringBuilder();

        private AccumulatingListener(WebSocketHandler handler) {
            this.handler = handler;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    handler.onMessage(message);
                } catch (RuntimeException ignored) {
                    // 메시지 처리 실패가 연결을 끊지 않도록 한다.
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handler.onClose(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handler.onError(error);
        }
    }

    /** {@link WebSocket} 을 {@link WebSocketSession} 으로 감싼다. */
    private static final class JdkSession implements WebSocketSession {

        private final WebSocket webSocket;
        private final Duration sendTimeout;

        private JdkSession(WebSocket webSocket, Duration sendTimeout) {
            this.webSocket = webSocket;
            this.sendTimeout = sendTimeout;
        }

        @Override
        public void send(String message) {
            try {
                webSocket.sendText(message, true).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                throw new BrokerTransientException("실시간 서버로 메시지를 보내지 못했습니다.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrokerTransientException("메시지 전송이 중단되었습니다.", e);
            }
        }

        @Override
        public boolean isOpen() {
            return !webSocket.isInputClosed() && !webSocket.isOutputClosed();
        }

        @Override
        public void close() {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "정상 종료");
            } catch (RuntimeException ignored) {
                webSocket.abort();
            }
        }
    }
}
