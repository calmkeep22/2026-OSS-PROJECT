package org.ossproject.sonification.timing;

import org.ossproject.sonification.analysis.GraphAnalyzer;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Assigns the same audible duration to every point. */
public final class EqualIntervalTimeMapping implements GraphTimeMapping {
    @Override public List<Duration> map(List<TimeSeriesSample> samples, Duration targetDuration) {
        List<TimeSeriesSample> checked = GraphAnalyzer.validate(samples);
        validateTargetDuration(targetDuration);
        long totalNanos = targetDuration.toNanos();
        if (totalNanos < checked.size()) {
            throw new IllegalArgumentException("targetDuration is too short for the sample count");
        }
        long base = totalNanos / checked.size();
        long remainder = totalNanos % checked.size();
        List<Duration> durations = new ArrayList<>(checked.size());
        for (int i = 0; i < checked.size(); i++) {
            durations.add(Duration.ofNanos(base + (i < remainder ? 1 : 0)));
        }
        return List.copyOf(durations);
    }

    static void validateTargetDuration(Duration targetDuration) {
        Objects.requireNonNull(targetDuration, "targetDuration");
        if (targetDuration.isZero() || targetDuration.isNegative()) {
            throw new IllegalArgumentException("targetDuration must be positive");
        }
    }
}
