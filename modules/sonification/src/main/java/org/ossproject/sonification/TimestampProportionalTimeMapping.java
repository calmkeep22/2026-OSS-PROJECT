package org.ossproject.sonification;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Preserves relative timestamp gaps while reserving a minimum audible duration for each point.
 * Equal timestamps receive the minimum weight and remain navigable.
 */
public final class TimestampProportionalTimeMapping implements GraphTimeMapping {
    public static final Duration DEFAULT_MINIMUM_FRAME_DURATION = Duration.ofMillis(50);
    private final Duration minimumFrameDuration;

    public TimestampProportionalTimeMapping() {
        this(DEFAULT_MINIMUM_FRAME_DURATION);
    }

    public TimestampProportionalTimeMapping(Duration minimumFrameDuration) {
        Objects.requireNonNull(minimumFrameDuration, "minimumFrameDuration");
        if (minimumFrameDuration.isZero() || minimumFrameDuration.isNegative()) {
            throw new IllegalArgumentException("minimumFrameDuration must be positive");
        }
        this.minimumFrameDuration = minimumFrameDuration;
    }

    @Override public List<Duration> map(List<TimeSeriesSample> samples, Duration targetDuration) {
        List<TimeSeriesSample> checked = GraphAnalyzer.validate(samples);
        EqualIntervalTimeMapping.validateTargetDuration(targetDuration);
        long totalNanos = targetDuration.toNanos();
        if (totalNanos < checked.size()) {
            throw new IllegalArgumentException("targetDuration is too short for the sample count");
        }

        long minimumNanos = Math.min(minimumFrameDuration.toNanos(), totalNanos / checked.size());
        long extraBudget = totalNanos - minimumNanos * checked.size();
        double[] weights = new double[checked.size()];
        double weightSum = 0;
        for (int i = 0; i < checked.size(); i++) {
            long gapNanos;
            if (i == 0) {
                gapNanos = 1;
            } else {
                try {
                    gapNanos = Duration.between(checked.get(i - 1).timestamp(),
                            checked.get(i).timestamp()).toNanos();
                } catch (ArithmeticException overflow) {
                    gapNanos = Long.MAX_VALUE;
                }
            }
            weights[i] = Math.max(1.0, gapNanos);
            weightSum += weights[i];
        }

        List<Duration> durations = new ArrayList<>(checked.size());
        long assigned = 0;
        for (int i = 0; i < checked.size(); i++) {
            long nanos;
            if (i == checked.size() - 1) {
                nanos = totalNanos - assigned;
            } else {
                nanos = minimumNanos + Math.round(extraBudget * (weights[i] / weightSum));
                long remainingMinimum = minimumNanos * (checked.size() - i - 1L);
                nanos = Math.max(minimumNanos,
                        Math.min(nanos, totalNanos - assigned - remainingMinimum));
            }
            durations.add(Duration.ofNanos(nanos));
            assigned += nanos;
        }
        return List.copyOf(durations);
    }
}
