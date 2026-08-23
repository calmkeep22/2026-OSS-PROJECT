package org.ossproject.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 인식기가 같은 말을 되풀이해 냈을 때.
 *
 * <p>실제로 났던 고장이다. 2.5초짜리 발화가 "이상감지" 쉰여섯 번으로 왔고, 확신도가
 * 0.96 이라 다른 방어를 전부 통과했다. 앱은 그것을 그대로 소리내어 읽었다.
 */
class RepeatedTranscriptTest {

    private static final String LOOPED = "이상감지, ".repeat(55) + "이상";

    @Test
    @DisplayName("되풀이된 말을 통째로 되읽지 않는다")
    void doesNotReadBackTheWholeLoop() {
        VoiceCommand command = new VoiceCommandParser(java.util.List.of()).parse(LOOPED);

        String spoken = command.confirmationQuestion();

        assertTrue(spoken.length() < 100,
                "되읽기가 " + spoken.length() + "자다. 사용자가 이걸 끝까지 듣게 된다");
        assertTrue(spoken.contains("…"), "잘렸다는 표시가 있어야 한다");
    }

    /** 줄여도 명령은 살아 있어야 한다. 되풀이된 그 말이 사용자가 한 말이다. */
    @Test
    @DisplayName("되풀이돼도 명령은 알아본다")
    void stillUnderstandsTheCommand() {
        assertEquals(Intent.OPEN_ANOMALY,
                new VoiceCommandParser(java.util.List.of()).parse(LOOPED).intent());
    }

    @Test
    @DisplayName("짧은 말은 그대로 되읽는다")
    void keepsShortSpeechIntact() {
        String spoken = new VoiceCommandParser(java.util.List.of())
                .parse("관심종목 보여줘").confirmationQuestion();

        assertTrue(spoken.contains("관심종목 보여줘"));
        assertFalse(spoken.contains("…"));
    }
}
