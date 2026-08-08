package org.ossproject.sonification.model;

import java.time.Instant;
import java.util.Objects;

/** One positive numeric observation in an identified, timestamped stream. */
public record TimeSeriesSample(String streamKey, double value, Instant timestamp) {
    public TimeSeriesSample {
        if (streamKey == null || streamKey.isBlank()) {
            throw new IllegalArgumentException("streamKey is required");
        }
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("value must be finite and greater than zero");
        }
        streamKey = streamKey.trim();
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
