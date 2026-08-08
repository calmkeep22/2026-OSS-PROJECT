package org.ossproject.sonification.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Complete output instruction for one glide from the previous pitch to the current value's pitch. */
public record GraphAudioFrame(
        String streamKey,
        double fromFrequencyHz,
        double toFrequencyHz,
        double percentFromReference,
        double normalizedPosition,
        double currentValue,
        Duration duration,
        Instant timestamp
) {
    public GraphAudioFrame {
        if (streamKey == null || streamKey.isBlank()) throw new IllegalArgumentException("streamKey is required");
        streamKey = streamKey.trim();
        if (!(Double.isFinite(fromFrequencyHz) && fromFrequencyHz > 0
                && Double.isFinite(toFrequencyHz) && toFrequencyHz > 0)) {
            throw new IllegalArgumentException("frame frequencies must be finite and positive");
        }
        if (!Double.isFinite(percentFromReference)) {
            throw new IllegalArgumentException("percentFromReference must be finite");
        }
        if (!Double.isFinite(normalizedPosition) || normalizedPosition < -1 || normalizedPosition > 1) {
            throw new IllegalArgumentException("normalizedPosition must be between -1 and 1");
        }
        if (!Double.isFinite(currentValue) || currentValue <= 0) {
            throw new IllegalArgumentException("currentValue must be finite and positive");
        }
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(timestamp, "timestamp");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
