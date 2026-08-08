package org.ossproject.kiwoom.stream;

import java.net.URI;

/**
 * WebSocket 연결 생성기.
 *
 * <p>테스트에서 실제 네트워크 없이 재연결 동작을 검증하기 위해 분리했다.
 */
@FunctionalInterface
public interface WebSocketConnector {

    /**
     * 연결을 만든다.
     *
     * @throws org.ossproject.broker.BrokerTransientException 연결에 실패한 경우
     */
    WebSocketSession connect(URI uri, WebSocketHandler handler);
}
