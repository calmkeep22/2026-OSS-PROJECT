package org.ossproject.accessibility.notification;

public enum SoundCue {
    SUCCESS,
    WARNING,
    ERROR,
    ANOMALY_HIGH,
    CONNECTION_LOST,
    CONNECTION_RESTORED,
    ORDER_FILLED,
    ORDER_REJECTED,

    /**
     * 마이크가 열렸다. 지금부터 말하면 된다.
     *
     * <p>말로 알릴 수 없어서 있는 신호다. "듣고 있습니다" 를 읽어 주면 스피커로 나간
     * 그 말을 마이크가 그대로 녹음한다. 실제로 그래서 사용자가 입도 떼기 전에 앱의
     * 목소리가 명령으로 들어갔다.
     *
     * <p>성공음을 대신 쓰지 않는다. 화면을 볼 수 없는 사용자에게 성공음은 무언가
     * 이루어졌다는 뜻이지 이제 말하라는 뜻이 아니다.
     */
    LISTENING
}
