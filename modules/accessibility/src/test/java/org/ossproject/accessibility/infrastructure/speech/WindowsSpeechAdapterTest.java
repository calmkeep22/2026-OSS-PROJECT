package org.ossproject.accessibility.infrastructure.speech;

import org.junit.jupiter.api.Test;
import org.ossproject.accessibility.notification.SpeechOptions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsSpeechAdapterTest {
    @Test void encodesTheWholePowerShellScriptAsUtf16WithoutRawUserText() {
        String text = "삼성전자 현재가 안내";
        String command = WindowsSpeechAdapter.encodedCommand(
                SpeechOptions.DEFAULT.withVoiceName("Microsoft Heami Desktop"), text);
        String decodedScript = new String(Base64.getDecoder().decode(command), StandardCharsets.UTF_16LE);
        String encodedVoice = Base64.getEncoder().encodeToString(
                "Microsoft Heami Desktop".getBytes(StandardCharsets.UTF_8));
        String encodedText = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));

        assertTrue(decodedScript.contains("System.Speech.Synthesis.SpeechSynthesizer"));
        assertTrue(decodedScript.contains(encodedVoice));
        assertFalse(decodedScript.contains(text));
        assertTrue(decodedScript.contains(encodedText));
    }
}
