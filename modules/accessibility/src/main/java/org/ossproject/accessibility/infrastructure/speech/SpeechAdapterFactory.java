package org.ossproject.accessibility.infrastructure.speech;

import org.ossproject.accessibility.port.SpeechPort;

import java.util.Locale;

public final class SpeechAdapterFactory {
    private SpeechAdapterFactory() {}
    public static SpeechPort create() {
        return createForOs(System.getProperty("os.name", ""));
    }

    static SpeechPort createForOs(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new WindowsSpeechAdapter();
        if (os.contains("mac") || os.contains("darwin")) return new MacSpeechAdapter();
        if (os.contains("nux")) return new LinuxSpeechAdapter();
        return new SilentSpeechAdapter();
    }
}
