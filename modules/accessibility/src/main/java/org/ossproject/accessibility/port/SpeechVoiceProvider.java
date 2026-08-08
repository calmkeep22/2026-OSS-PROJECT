package org.ossproject.accessibility.port;

import org.ossproject.accessibility.notification.SpeechVoice;
import java.util.List;

public interface SpeechVoiceProvider {
    List<SpeechVoice> availableVoices();
}
