package org.ossproject.sonification.model;

import java.util.List;
import java.util.Objects;

/** Maps positive values to the normalized audible position {@code -1..1}. */
public record GraphValueScale(
        GraphScaleMode mode,
        double referenceValue,
        double lowerValue,
        double upperValue
) {
    public GraphValueScale {
        Objects.requireNonNull(mode, "mode");
        if (!(Double.isFinite(lowerValue)
                && Double.isFinite(referenceValue)
                && Double.isFinite(upperValue)
                && lowerValue > 0
                && lowerValue < referenceValue
                && referenceValue < upperValue)) {
            throw new IllegalArgumentException("scale values must be positive and strictly increasing");
        }
    }

    public static GraphValueScale automatic(List<TimeSeriesSample> samples) {
        List<TimeSeriesSample> checked = List.copyOf(Objects.requireNonNull(samples, "samples"));
        if (checked.isEmpty()) throw new IllegalArgumentException("samples must not be empty");
        double lower = checked.stream().mapToDouble(TimeSeriesSample::value).min().orElseThrow();
        double upper = checked.stream().mapToDouble(TimeSeriesSample::value).max().orElseThrow();
        if (Double.compare(lower, upper) == 0) {
            double padding = Math.max(lower * 0.01, Math.ulp(lower) * 16);
            return new GraphValueScale(GraphScaleMode.AUTOMATIC, lower, lower - padding, lower + padding);
        }
        return new GraphValueScale(GraphScaleMode.AUTOMATIC, (lower + upper) / 2.0, lower, upper);
    }

    public static GraphValueScale percentFromReference(double referenceValue, double percentRange) {
        if (!Double.isFinite(referenceValue) || referenceValue <= 0) {
            throw new IllegalArgumentException("referenceValue must be finite and positive");
        }
        if (!Double.isFinite(percentRange) || percentRange <= 0 || percentRange >= 100) {
            throw new IllegalArgumentException("percentRange must be between zero and one hundred");
        }
        double ratio = percentRange / 100.0;
        return new GraphValueScale(GraphScaleMode.PERCENT_FROM_REFERENCE, referenceValue,
                referenceValue * (1 - ratio), referenceValue * (1 + ratio));
    }

    public double normalizedPosition(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("value must be finite and positive");
        }
        double position = value >= referenceValue
                ? (value - referenceValue) / (upperValue - referenceValue)
                : (value - referenceValue) / (referenceValue - lowerValue);
        return Math.max(-1, Math.min(1, position));
    }

    public double percentFromReference(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("value must be finite and positive");
        }
        return (value - referenceValue) / referenceValue * 100.0;
    }
}
