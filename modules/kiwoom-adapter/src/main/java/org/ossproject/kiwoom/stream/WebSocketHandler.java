package org.ossproject.kiwoom.stream;

/** WebSocket 연결에서 올라오는 사건. */
interface WebSocketHandler {

    void onMessage(String message);

    void onClose(int statusCode, String reason);

    void onError(Throwable error);
}
