package org.ossproject.sonification.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphSonificationModelTest {
    @Test void mapsPercentChangesOntoALogarithmicPitchRange() {
        GraphSonificationConfig config = GraphSonificationConfig.DEFAULT;
        assertEquals(220, config.frequencyFor(-5), 0.001);
        assertEquals(440, config.frequencyFor(0), 0.001);
        assertEquals(880, config.frequencyFor(5), 0.001);
        assertEquals(Math.sqrt(440 * 880), config.frequencyFor(2.5), 0.001);
        assertEquals(220, config.frequencyFor(-50), 0.001);
        assertEquals(880, config.frequencyFor(50), 0.001);
    }

    @Test void validatesConfigurationSamplesAndFrames() {
        assertThrows(IllegalArgumentException.class, () -> new GraphSonificationConfig(
                440, 220, 880, 5, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new GraphSonificationConfig(
                220, 440, 880, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new TimeSeriesSample("", 100, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new TimeSeriesSample("key", 0, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new GraphAudioFrame(
                "key", 440, 880, 5, 2, 100, Duration.ofSeconds(1), Instant.now()));
    }

    @Test void supportsAutomaticAndReferenceBasedValueScales() {
        TimeSeriesSample low = new TimeSeriesSample("key", 100, Instant.parse("2026-08-08T00:00:00Z"));
        TimeSeriesSample high = new TimeSeriesSample("key", 110, Instant.parse("2026-08-08T00:00:01Z"));
        GraphValueScale automatic = GraphValueScale.automatic(List.of(low, high));
        assertEquals(GraphScaleMode.AUTOMATIC, automatic.mode());
        assertEquals(-1, automatic.normalizedPosition(100), 0.001);
        assertEquals(0, automatic.normalizedPosition(105), 0.001);
        assertEquals(1, automatic.normalizedPosition(110), 0.001);

        GraphValueScale fixed = GraphValueScale.percentFromReference(100, 5);
        assertEquals(GraphScaleMode.PERCENT_FROM_REFERENCE, fixed.mode());
        assertEquals(-1, fixed.normalizedPosition(95), 0.001);
        assertEquals(0.5, fixed.normalizedPosition(102.5), 0.001);
        assertEquals(5, fixed.percentFromReference(105), 0.001);
    }
}
