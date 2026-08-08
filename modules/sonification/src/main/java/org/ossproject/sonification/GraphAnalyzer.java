package org.ossproject.sonification;

import org.ossproject.sonification.model.GraphSummary;
import org.ossproject.sonification.model.GraphTrend;
import org.ossproject.sonification.model.TimeSeriesSample;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Calculates textual-summary facts from an ordered positive time series. */
public final class GraphAnalyzer {
    private static final double FLAT_THRESHOLD_PERCENT = 0.05;

    private GraphAnalyzer() {}

    /** Summarizes trend, endpoints, extrema, and the largest adjacent change. */
    public static GraphSummary summarize(List<TimeSeriesSample> samples) {
        List<TimeSeriesSample> checked = validate(samples);
        TimeSeriesSample first = checked.get(0);
        TimeSeriesSample last = checked.get(checked.size() - 1);
        TimeSeriesSample minimum = checked.stream().min(Comparator.comparingDouble(TimeSeriesSample::value)).orElseThrow();
        TimeSeriesSample maximum = checked.stream().max(Comparator.comparingDouble(TimeSeriesSample::value)).orElseThrow();
        double totalChange = percentChange(first.value(), last.value());
        GraphTrend trend = Math.abs(totalChange) <= FLAT_THRESHOLD_PERCENT
                ? GraphTrend.FLAT : totalChange > 0 ? GraphTrend.RISING : GraphTrend.FALLING;

        double largestStep = 0;
        TimeSeriesSample largestStepEnd = first;
        for (int i = 1; i < checked.size(); i++) {
            double step = percentChange(checked.get(i - 1).value(), checked.get(i).value());
            if (Math.abs(step) > Math.abs(largestStep)) {
                largestStep = step;
                largestStepEnd = checked.get(i);
            }
        }
        return new GraphSummary(first.streamKey(), checked.size(), first, last, minimum, maximum,
                trend, totalChange, largestStep, largestStepEnd);
    }

    /** Returns an immutable validated copy with one stream key and nondecreasing timestamps. */
    public static List<TimeSeriesSample> validate(List<TimeSeriesSample> samples) {
        List<TimeSeriesSample> checked = List.copyOf(Objects.requireNonNull(samples, "samples"));
        if (checked.isEmpty()) throw new IllegalArgumentException("samples must not be empty");
        String streamKey = checked.get(0).streamKey();
        for (int i = 0; i < checked.size(); i++) {
            TimeSeriesSample sample = checked.get(i);
            if (!streamKey.equals(sample.streamKey())) {
                throw new IllegalArgumentException("all samples must use the same streamKey");
            }
            if (i > 0 && sample.timestamp().isBefore(checked.get(i - 1).timestamp())) {
                throw new IllegalArgumentException("samples must be ordered by timestamp");
            }
        }
        return checked;
    }

    private static double percentChange(double from, double to) {
        return (to - from) / from * 100.0;
    }
}
