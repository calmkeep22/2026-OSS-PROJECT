package org.ossproject.voice.javasound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 마이크 없이 검사할 수 있는 부분.
 *
 * <p>소리를 실제로 받는 것은 CI 에서 검사할 수 없다. 하지만 받은 소리를 WAV 로
 * 만드는 것과 크기를 재는 것은 순수한 계산이라 검사할 수 있고, 여기가 틀리면
 * 서버가 400 을 돌려주는데 사용자에게는 "인식 실패" 로만 보인다.
 */
class MicrophoneCaptureTest {

    @DisplayName("만든 WAV 를 다시 읽을 수 있다")
    @Test
    void producesReadableWav() throws Exception {
        // 16kHz 16비트 모노로 1초. 프레임 하나가 2바이트다.
        byte[] pcm = new byte[16_000 * 2];

        byte[] wav = MicrophoneCapture.toWav(pcm);

        try (AudioInputStream read = AudioSystem.getAudioInputStream(
                new ByteArrayInputStream(wav))) {
            assertEquals(16_000f, read.getFormat().getSampleRate(),
                    "whisper 는 16kHz 로 학습돼 있다");
            assertEquals(1, read.getFormat().getChannels(), "모노여야 한다");
            assertEquals(16, read.getFormat().getSampleSizeInBits());
            assertEquals(16_000, read.getFrameLength(), "1초가 들어가야 한다");
        }
    }

    @DisplayName("WAV 는 헤더가 붙어 원본보다 커진다")
    @Test
    void wavCarriesAHeader() {
        byte[] pcm = new byte[1_000];

        byte[] wav = MicrophoneCapture.toWav(pcm);

        assertTrue(wav.length > pcm.length, "헤더 없는 알맹이를 보내면 서버가 못 읽는다");
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
    }

    @DisplayName("무음은 0, 최대 진폭은 1 에 가깝다")
    @Test
    void measuresLoudness() {
        byte[] silence = new byte[200];

        byte[] loud = new byte[200];
        for (int index = 0; index + 1 < loud.length; index += 2) {
            loud[index] = (byte) 0xff;        // 32767 리틀엔디언
            loud[index + 1] = (byte) 0x7f;
        }

        assertEquals(0.0, MicrophoneCapture.loudness(silence, silence.length), 0.0001);
        assertEquals(1.0, MicrophoneCapture.loudness(loud, loud.length), 0.01);
    }

    /**
     * 음수 표본을 부호 없는 값으로 읽으면 조용한 소리가 아주 큰 소리로 보인다.
     * 그러면 배경 소음에도 말이 시작된 것으로 판단해 녹음이 끝나지 않는다.
     */
    @DisplayName("음수 표본을 부호 있는 값으로 읽는다")
    @Test
    void readsSamplesAsSigned() {
        byte[] negative = new byte[200];
        for (int index = 0; index + 1 < negative.length; index += 2) {
            negative[index] = (byte) 0x00;    // -32768 리틀엔디언
            negative[index + 1] = (byte) 0x80;
        }

        double level = MicrophoneCapture.loudness(negative, negative.length);

        assertTrue(level <= 1.01, "부호를 잘못 읽으면 1 을 크게 넘는다: " + level);
        assertTrue(level > 0.9, "최대 진폭인데 작게 나온다: " + level);
    }

    @DisplayName("빈 소리에서도 터지지 않는다")
    @Test
    void handlesEmptyChunk() {
        assertEquals(0.0, MicrophoneCapture.loudness(new byte[0], 0));
    }
}
