package org.ossproject.accessibility.infrastructure.speech;

import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;
import org.ossproject.accessibility.notification.SpeechVoice;
import java.util.List;

public final class SilentSpeechAdapter implements SpeechPort, SpeechVoiceProvider {
    @Override public void speak(String text) {}
    @Override public void stop() {}
    @Override public List<SpeechVoice> availableVoices() { return List.of(); }
}
