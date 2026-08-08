package org.ossproject.accessibility;

import org.junit.jupiter.api.Test;
import org.ossproject.accessibility.notification.SpeechOptions;

import static org.junit.jupiter.api.Assertions.*;

class SpeechOptionsTest {
    @Test void defaultsToNormalRateFullVolumeAndSystemVoice() {
        assertEquals(1.0, SpeechOptions.DEFAULT.rate());
        assertEquals(100, SpeechOptions.DEFAULT.volume());
        assertNull(SpeechOptions.DEFAULT.voiceName());
    }

    @Test void withMethodsProduceIndependentImmutableCopies() {
        SpeechOptions options = SpeechOptions.DEFAULT.withRate(1.5).withVolume(60).withVoiceName("Heami");
        assertEquals(1.5, options.rate());
        assertEquals(60, options.volume());
        assertEquals("Heami", options.voiceName());
        assertEquals(1.0, SpeechOptions.DEFAULT.rate(), "DEFAULT must stay unchanged");
    }

    @Test void rejectsRateOutsideSupportedRange() {
        assertThrows(IllegalArgumentException.class, () -> SpeechOptions.DEFAULT.withRate(0.4));
        assertThrows(IllegalArgumentException.class, () -> SpeechOptions.DEFAULT.withRate(2.1));
    }

    @Test void rejectsVolumeOutsideZeroToHundred() {
        assertThrows(IllegalArgumentException.class, () -> SpeechOptions.DEFAULT.withVolume(-1));
        assertThrows(IllegalArgumentException.class, () -> SpeechOptions.DEFAULT.withVolume(101));
    }

    @Test void blankVoiceNameIsNormalizedToNull() {
        assertNull(SpeechOptions.DEFAULT.withVoiceName("  ").voiceName());
    }
}
