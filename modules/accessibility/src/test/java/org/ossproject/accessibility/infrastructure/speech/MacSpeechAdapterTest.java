package org.ossproject.accessibility.infrastructure.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacSpeechAdapterTest {
    @Test void normalRateMapsToAppleDefaultWordsPerMinute() {
        assertEquals(180, MacSpeechAdapter.toWordsPerMinute(1.0));
    }

    @Test void rateIsClampedToSupportedRange() {
        assertEquals(90, MacSpeechAdapter.toWordsPerMinute(0.1));
        assertEquals(360, MacSpeechAdapter.toWordsPerMinute(5.0));
    }
}
