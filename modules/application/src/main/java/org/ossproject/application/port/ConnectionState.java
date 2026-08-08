package org.ossproject.application.port;

/**
 * 실시간 연결 상태.
 *
 * <p>화면 계층은 이 상태를 접근성 알림으로 옮긴다. 시각장애 사용자는 연결이 끊긴 것을
 * 눈으로 확인할 수 없으므로, 끊김과 복구를 반드시 소리와 음성으로 알려야 한다.
 */
public enum ConnectionState {
    DISCONNECTED("연결 끊김"),
    CONNECTING("연결 중"),
    CONNECTED("연결됨"),
    RECONNECTING("재연결 중"),
    FAILED("연결 실패");

    private final String displayName;

    ConnectionState(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isUsable() {
        return this == CONNECTED;
    }
}
