package org.ossproject.voice.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.ossproject.voice.AudioCapturePort;
import org.ossproject.voice.Transcript;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 진짜 AI 서비스에 붙여 보는 확인. 평소에는 돌지 않는다.
 *
 * <p>서버를 띄우고 모델을 내려받아야 하므로 보통의 검사에 섞을 수 없다. 대신 이
 * 창구를 고칠 때마다 손으로 한 번 돌린다. 이 프로젝트에서 문서만 보고 맞다고 여긴
 * 것은 매번 틀렸다 — 필드 이름도, 응답 모양도.
 *
 * <pre>
 *   cd ai-service &amp;&amp; python server.py --port 8765
 *   ./gradlew :modules:voice-input-http:test --tests "*LiveTranscribeProbe" \
 *       -Dvoice.live=8765 -Dvoice.wav=&lt;WAV 가 든 폴더&gt;
 * </pre>
 */
@EnabledIfSystemProperty(named = "voice.live", matches = ".+")
class LiveTranscribeProbe {

    /** 미리 녹음해 둔 WAV 를 마이크인 척 돌려준다. */
    private record RecordedFile(Path path) implements AudioCapturePort {
        @Override public byte[] recordUtterance(Duration limit) {
            try {
                return Files.readAllBytes(path);
            } catch (IOException unreadable) {
                throw new IllegalStateException(path + " 를 읽지 못했습니다.", unreadable);
            }
        }

        @Override public boolean available() { return true; }
        @Override public String unavailableReason() { return ""; }
        @Override public void close() { }
    }

    private static URI base() {
        return URI.create("http://127.0.0.1:" + System.getProperty("voice.live"));
    }

    @Test
    @DisplayName("어휘를 물리고 실제 발화를 보내 글로 받는다")
    void transcribesRealSpeech() throws IOException {
        Path folder = Path.of(System.getProperty("voice.wav", "."));
        List<Path> wavs;
        try (var found = Files.list(folder)) {
            wavs = found.filter(path -> path.toString().endsWith(".wav")).sorted().toList();
        }
        assertFalse(wavs.isEmpty(), folder + " 에 WAV 가 없습니다.");

        HttpVoiceInputAdapter voice =
                new HttpVoiceInputAdapter(base(), new RecordedFile(wavs.get(0)));
        assertTrue(voice.available(), "서버를 쓸 수 없습니다: " + voice.unavailableReason());

        voice.useVocabulary(List.of("삼성전자", "SK하이닉스", "에스케이하이닉스", "카카오",
                "관심종목", "청각차트", "현재가", "뉴스", "매수", "매도", "계좌"));

        for (Path wav : wavs) {
            Transcript heard = new HttpVoiceInputAdapter(base(), new RecordedFile(wav))
                    .listen(Duration.ofSeconds(10));
            System.out.printf("%s -> %s (확신도 %s)%n",
                    wav.getFileName(), heard.text(),
                    heard.confidenceKnown() ? heard.confidence() : "모름");
            assertTrue(heard.heardSomething(), wav + " 에서 아무 말도 못 알아들었습니다.");
            assertTrue(heard.confidenceKnown(), "확신도가 없으면 되물을지 정할 수 없습니다.");
        }
    }
}
