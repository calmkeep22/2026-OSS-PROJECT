package org.ossproject.sonification;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.model.GraphTrend;
import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphAnalyzerTest {
    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test void summarizesTrendExtremesAndLargestStep() {
        var summary = GraphAnalyzer.summarize(List.of(sample("005930", 0, 100),
                sample("005930", 1, 90), sample("005930", 2, 110)));

        assertEquals(GraphTrend.RISING, summary.trend());
        assertEquals(10, summary.totalChangePercent(), 0.001);
        assertEquals(90, summary.minimum().value(), 0.001);
        assertEquals(110, summary.maximum().value(), 0.001);
        assertEquals(22.222, summary.largestStepPercent(), 0.001);
        assertEquals(START.plusSeconds(2), summary.largestStepEnd().timestamp());
    }

    @Test void rejectsMixedOrOutOfOrderSeries() {
        assertThrows(IllegalArgumentException.class, () -> GraphAnalyzer.summarize(List.of(
                sample("005930", 0, 100), sample("000660", 1, 101))));
        assertThrows(IllegalArgumentException.class, () -> GraphAnalyzer.summarize(List.of(
                sample("005930", 1, 100), sample("005930", 0, 101))));
    }

    private static TimeSeriesSample sample(String key, long second, double value) {
        return new TimeSeriesSample(key, value, START.plusSeconds(second));
    }
}
