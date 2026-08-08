package org.ossproject.application.port;

/** 실시간 연결 상태 변화 수신자. */
@FunctionalInterface
public interface ConnectionListener {

    /**
     * 연결 상태가 바뀔 때 호출된다.
     *
     * @param state  새 상태
     * @param detail 사용자에게 읽어 줄 수 있는 부가 설명. 없으면 {@code null}
     */
    void onConnectionStateChanged(ConnectionState state, String detail);
}
