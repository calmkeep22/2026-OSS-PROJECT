package org.ossproject.accessibility.infrastructure.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinuxSpeechAdapterTest {
    @Test void normalRateMapsToZero() {
        assertEquals(0, LinuxSpeechAdapter.toSpdRate(1.0));
    }

    @Test void extremeRatesMapToFullRange() {
        assertEquals(-100, LinuxSpeechAdapter.toSpdRate(0.5));
        assertEquals(100, LinuxSpeechAdapter.toSpdRate(2.0));
    }

    @Test void volumeIsStretchedOntoNegativeHundredToHundred() {
        assertEquals(-100, LinuxSpeechAdapter.toSpdVolume(0));
        assertEquals(100, LinuxSpeechAdapter.toSpdVolume(100));
        assertEquals(0, LinuxSpeechAdapter.toSpdVolume(50));
    }
}
