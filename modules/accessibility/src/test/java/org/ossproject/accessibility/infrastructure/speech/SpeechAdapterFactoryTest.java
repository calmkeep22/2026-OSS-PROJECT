package org.ossproject.accessibility.infrastructure.speech;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SpeechAdapterFactoryTest {
    @Test void selectsAnAdapterForEachSupportedOperatingSystem() {
        assertInstanceOf(WindowsSpeechAdapter.class, SpeechAdapterFactory.createForOs("Windows 11"));
        assertInstanceOf(MacSpeechAdapter.class, SpeechAdapterFactory.createForOs("Mac OS X"));
        assertInstanceOf(LinuxSpeechAdapter.class, SpeechAdapterFactory.createForOs("Linux"));
        assertInstanceOf(SilentSpeechAdapter.class, SpeechAdapterFactory.createForOs("Unknown"));
    }
}
