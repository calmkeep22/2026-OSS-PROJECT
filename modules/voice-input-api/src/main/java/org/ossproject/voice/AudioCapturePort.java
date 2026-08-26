package org.ossproject.voice;

import java.time.Duration;
import java.util.List;

/**
 * 마이크에서 한마디를 받아 온다.
 *
 * <p>인식기와 나눠 둔 이유는 고장 나는 지점이 다르기 때문이다. 마이크가 없는 것과
 * 인식 서버가 안 뜬 것은 사용자가 해야 할 일이 다르다. 한 덩어리로 두면 "음성 인식을
 * 쓸 수 없습니다" 라는 같은 말만 나오고, 마이크를 꽂으면 될 일인지 알 수 없다.
 */
public interface AudioCapturePort extends AutoCloseable {

    /**
     * 이 크기를 넘어야 말로 친다.
     *
     * <p>마이크 확인 화면과 실제 말 판정이 같은 값을 써야 한다. 따로 두면 "막대가 문턱을
     * 넘는데 인식은 안 되는" 상태가 생기고, 사용자는 무엇을 믿어야 할지 알 수 없다.
     */
    double SPEECH_FLOOR = 0.012;

    /**
     * 마이크로 들어오는 소리 크기를 계속 알려준다.
     *
     * <p>{@link #recordUtterance(Duration)} 로는 이걸 할 수 없다. 다 녹음한 다음에야
     * 돌려주기 때문에, 말하는 동안 잡히고 있는지를 볼 수 없다.
     *
     * <p>크기는 0에서 1 사이다. {@link #SPEECH_FLOOR} 를 넘으면 말로 친다.
     *
     * <p>{@code onLevel} 은 <b>오디오 스레드에서</b> 불린다. 화면을 건드리려면 부르는
     * 쪽이 화면 스레드로 옮겨야 한다.
     *
     * @return 닫으면 멈춘다. 반드시 닫아야 한다 — 안 닫으면 마이크를 계속 붙들고 있다
     */
    default AutoCloseable monitorLevel(java.util.function.DoubleConsumer onLevel) {
        return () -> { };
    }

    /**
     * 한마디를 녹음한다. 말이 끝나면 {@code limit} 을 기다리지 않고 바로 돌아온다.
     *
     * <p>정해진 시간을 꽉 채워 녹음하면 명령 하나에 녹음 대기까지 얹혀 느려진다.
     * 말이 그친 것을 알아채고 끊어야 한다.
     *
     * <p>부르는 쪽을 막는다. 화면 스레드에서 부르면 안 된다.
     *
     * @return WAV 바이트. 아무 소리도 못 받았으면 길이 0.
     */
    byte[] recordUtterance(Duration limit);

    /**
     * 고를 수 있는 입력 장치 이름.
     *
     * <p>기본 장치를 알아서 쓰면 될 것 같지만 그렇지 않다. 실측에서 자바가 고른 기본
     * 장치는 소리가 하나도 들어오지 않는 "주 사운드 캡처 드라이버" 였고, 정작 사용자가
     * 쓰고 있던 이어폰은 목록의 다른 자리에 있었다. 자바의 기본 장치는 운영체제의 기본
     * 장치를 따라가지 않는다.
     */
    default List<String> devices() {
        return List.of();
    }

    /**
     * 쓸 장치를 정한다. 비워 두면 기본 장치를 쓴다.
     *
     * <p>블루투스 이어폰은 연결이 끊긴다. 정해 둔 장치가 사라졌으면 기본으로 돌아가되,
     * 조용히 바꾸면 안 된다. 사용자는 왜 갑자기 안 되는지 알 수 없다.
     */
    default void useDevice(String deviceName) {
    }

    /** 지금 쓰는 장치. 기본 장치면 빈 문자열. */
    default String currentDevice() {
        return "";
    }

    boolean available();

    /** 왜 못 쓰는지. 쓸 수 있으면 빈 문자열. 사용자에게 그대로 읽어 준다. */
    String unavailableReason();

    @Override
    void close();
}
