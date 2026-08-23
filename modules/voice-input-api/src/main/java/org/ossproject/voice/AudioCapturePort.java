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
