package org.ossproject.kiwoom.stream;

/** 열려 있는 WebSocket 연결 하나. */
interface WebSocketSession extends AutoCloseable {

    void send(String message);

    boolean isOpen();

    @Override
    void close();
}
