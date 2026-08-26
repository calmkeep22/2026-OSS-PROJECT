package org.ossproject.voice.javasound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.voice.AudioCapturePort;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 마이크 확인이 쓰는 문턱값.
 *
 * <p>확인 화면과 실제 말 판정이 다른 값을 쓰면 최악이다 — 막대는 문턱을 넘는데 인식은
 * 안 되고, 사용자는 마이크가 문제인지 발음이 문제인지 영영 가릴 수 없다. 두 값이
 * 갈라지는 순간을 여기서 잡는다.
 */
class MicrophoneLevelTest {

    @Test
    @DisplayName("확인 화면과 말 판정이 같은 문턱을 쓴다")
    void theMeterAndTheDetectorAgree() {
        UtteranceDetector detector = new UtteranceDetector(50);
        // 완전한 무음으로 배경을 재면 문턱은 바닥값 그대로가 된다.
        for (int spent = 0; spent < 300; spent += 50) {
            detector.accept(0.0);
        }

        assertEquals(AudioCapturePort.SPEECH_FLOOR, detector.threshold(), 1e-9,
                "확인 막대가 넘었다고 한 소리는 인식기도 말로 쳐야 한다");
    }

    @Test
    @DisplayName("문턱 바로 아래는 말이 아니다")
    void justBelowTheFloorIsNotSpeech() {
        UtteranceDetector detector = new UtteranceDetector(50);
        for (int spent = 0; spent < 300; spent += 50) {
            detector.accept(0.0);
        }

        detector.accept(AudioCapturePort.SPEECH_FLOOR - 0.001);

        assertNotEquals(UtteranceDetector.State.SPEAKING, detector.state());
    }

    /** 붙이지 않은 어댑터도 있다. 부르는 쪽이 터지지 않아야 한다. */
    @Test
    @DisplayName("크기 알림을 못 하는 어댑터도 닫을 수 있는 것을 준다")
    void defaultMonitorIsSafeToClose() throws Exception {
        AudioCapturePort silent = new AudioCapturePort() {
            @Override public byte[] recordUtterance(java.time.Duration limit) { return new byte[0]; }
            @Override public boolean available() { return false; }
            @Override public String unavailableReason() { return "없음"; }
            @Override public void close() { }
        };

        AutoCloseable handle = silent.monitorLevel(level -> fail("올 값이 없다"));

        assertNotNull(handle);
        handle.close();
    }
}
