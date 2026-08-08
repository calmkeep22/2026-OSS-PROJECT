package org.ossproject.sonification;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphPlaybackPlannerTest {
    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test void lttbKeepsEndpointsAndAHighImpactSpike() {
        List<TimeSeriesSample> samples = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            samples.add(sample(i, i == 50 ? 180 : 100 + Math.sin(i / 5.0)));
        }

        List<TimeSeriesSample> reduced = new LargestTriangleThreeBucketsReducer().reduce(samples, 12);

        assertEquals(12, reduced.size());
        assertEquals(samples.get(0), reduced.get(0));
        assertEquals(samples.get(100), reduced.get(11));
        assertTrue(reduced.contains(samples.get(50)), "the isolated price spike should remain audible");
    }

    @Test void timestampMappingMakesLongerMarketGapsAudiblyLonger() {
        List<TimeSeriesSample> samples = List.of(sample(0, 100), sample(1, 101), sample(11, 102));

        List<Duration> durations = new TimestampProportionalTimeMapping(Duration.ofMillis(100))
                .map(samples, Duration.ofMillis(1_500));

        assertEquals(3, durations.size());
        assertEquals(Duration.ofMillis(100), durations.get(0));
        assertTrue(durations.get(2).compareTo(durations.get(1)) > 0);
        assertEquals(Duration.ofMillis(1_500), durations.stream().reduce(Duration.ZERO, Duration::plus));
    }

    @Test void plannerKeepsAllSeekableSamplesButBoundsAudibleSamples() {
        List<TimeSeriesSample> samples = new ArrayList<>();
        for (int i = 0; i < 200; i++) samples.add(sample(i, 100 + i * 0.1));
        GraphPlaybackPlanner planner = new GraphPlaybackPlanner(
                new LargestTriangleThreeBucketsReducer(), new TimestampProportionalTimeMapping());

        GraphPlaybackPlan plan = planner.plan(samples, 24, Duration.ofSeconds(12));

        assertEquals(200, plan.sourceSamples().size());
        assertEquals(24, plan.playbackSamples().size());
        assertEquals(24, plan.frameDurations().size());
        assertEquals(Duration.ofSeconds(12),
                plan.frameDurations().stream().reduce(Duration.ZERO, Duration::plus));
        assertEquals(199, plan.sourceIndexForPlaybackIndex(23));
    }

    private static TimeSeriesSample sample(long seconds, double value) {
        return new TimeSeriesSample("005930", value, START.plusSeconds(seconds));
    }
}
