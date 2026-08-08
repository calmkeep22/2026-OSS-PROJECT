package org.ossproject.sonification.model;

import java.util.Objects;

/** Facts calculated from a complete source series for visible text or speech output. */
public record GraphSummary(
        String streamKey,
        int pointCount,
        TimeSeriesSample first,
        TimeSeriesSample last,
        TimeSeriesSample minimum,
        TimeSeriesSample maximum,
        GraphTrend trend,
        double totalChangePercent,
        double largestStepPercent,
        TimeSeriesSample largestStepEnd
) {
    public GraphSummary {
        if (streamKey == null || streamKey.isBlank()) throw new IllegalArgumentException("streamKey is required");
        if (pointCount < 1) throw new IllegalArgumentException("pointCount must be positive");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        Objects.requireNonNull(trend, "trend");
        Objects.requireNonNull(largestStepEnd, "largestStepEnd");
    }
}
