package org.ossproject.accessibility.infrastructure.speech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.accessibility.notification.SpeechOptions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 상주 TTS 프로세스에 보내는 한 줄 명령.
 *
 * <p>프로세스를 문장마다 새로 띄우던 것을 하나로 상주시키면서 명령 모양이 바뀌었다.
 * 예전에는 스크립트를 통째로 인코딩해 넘겼고, 지금은 이미 떠 있는 프로세스에 한 줄을
 * 흘려보낸다. 지켜야 할 성질은 그대로다.
 */
class WindowsSpeechAdapterTest {

    /**
     * 사용자 글자가 명령에 날것으로 들어가면 안 된다.
     *
     * <p>명령은 칸으로 쪼개 읽는다. 종목명이나 안내 문장에 칸·따옴표·줄바꿈이 들어가면
     * 자리가 밀려 엉뚱한 값이 속도나 목소리로 들어가고, 최악의 경우 뒤에 붙은 것이
     * 명령처럼 실행된다.
     */
    @Test
    @DisplayName("읽어 줄 글자를 감싸서 보낸다")
    void wrapsSpokenTextSoItCannotSplitTheCommand() {
        String text = "삼성전자 현재가 안내";

        String command = WindowsSpeechAdapter.command(
                SpeechOptions.DEFAULT.withVoiceName("Microsoft Heami Desktop"), text);

        assertFalse(command.contains(text), "글자가 날것으로 들어갔습니다: " + command);
        assertTrue(command.contains(
                Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("목소리 이름도 감싸서 보낸다")
    void wrapsTheVoiceName() {
        String voice = "Microsoft Heami Desktop";

        String command = WindowsSpeechAdapter.command(SpeechOptions.DEFAULT.withVoiceName(voice), text());

        assertFalse(command.contains(voice));
        assertTrue(command.contains(
                Base64.getEncoder().encodeToString(voice.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * 빈 값을 그대로 보내면 칸이 하나 사라져 뒤 자리가 밀린다. 자리를 지키는 표시를
     * 넣어야 프로세스가 다섯 칸으로 읽는다.
     */
    @Test
    @DisplayName("목소리를 안 고르면 자리를 비우지 않는다")
    void keepsEveryFieldEvenWithoutAVoice() {
        String command = WindowsSpeechAdapter.command(SpeechOptions.DEFAULT, text());

        assertEquals(5, command.split(" ").length, command);
        assertTrue(command.startsWith("SPEAK "));
    }

    @Test
    @DisplayName("줄바꿈이 섞여도 명령이 한 줄로 남는다")
    void staysOnOneLine() {
        String command = WindowsSpeechAdapter.command(
                SpeechOptions.DEFAULT, "첫 줄\n둘째 줄\r\n셋째 줄");

        assertFalse(command.contains("\n"), "줄이 나뉘면 뒤가 다음 명령으로 읽힙니다.");
        assertFalse(command.contains("\r"));
    }

    private static String text() {
        return "안내 문장";
    }
}
