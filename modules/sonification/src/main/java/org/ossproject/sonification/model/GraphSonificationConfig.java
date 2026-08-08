package org.ossproject.sonification.model;

import java.time.Duration;
import java.util.Objects;

/** Audible frequency range, percentage range, and default duration used to map graph values. */
public record GraphSonificationConfig(
        double lowerFrequencyHz,
        double centerFrequencyHz,
        double upperFrequencyHz,
        double percentRange,
        Duration frameDuration
) {
    public static final GraphSonificationConfig DEFAULT = new GraphSonificationConfig(
            220, 440, 880, 5.0, Duration.ofSeconds(1));

    public GraphSonificationConfig {
        if (!(Double.isFinite(lowerFrequencyHz)
                && Double.isFinite(centerFrequencyHz)
                && Double.isFinite(upperFrequencyHz)
                && lowerFrequencyHz > 0
                && lowerFrequencyHz < centerFrequencyHz
                && centerFrequencyHz < upperFrequencyHz)) {
            throw new IllegalArgumentException("frequencies must be positive and strictly increasing");
        }
        if (!Double.isFinite(percentRange) || percentRange <= 0) {
            throw new IllegalArgumentException("percentRange must be finite and positive");
        }
        Objects.requireNonNull(frameDuration, "frameDuration");
        if (frameDuration.isZero() || frameDuration.isNegative()) {
            throw new IllegalArgumentException("frameDuration must be positive");
        }
    }

    public double frequencyFor(double percentFromReference) {
        if (!Double.isFinite(percentFromReference)) {
            throw new IllegalArgumentException("percentFromReference must be finite");
        }
        return frequencyForNormalizedPosition(percentFromReference / percentRange);
    }

    public double frequencyForNormalizedPosition(double normalizedPosition) {
        if (!Double.isFinite(normalizedPosition)) {
            throw new IllegalArgumentException("normalizedPosition must be finite");
        }
        double position = Math.max(-1, Math.min(1, normalizedPosition));
        if (position >= 0) {
            return centerFrequencyHz * Math.pow(upperFrequencyHz / centerFrequencyHz, position);
        }
        return centerFrequencyHz * Math.pow(centerFrequencyHz / lowerFrequencyHz, position);
    }

    public double normalizedPosition(double percentFromReference) {
        if (!Double.isFinite(percentFromReference)) {
            throw new IllegalArgumentException("percentFromReference must be finite");
        }
        return Math.max(-1, Math.min(1, percentFromReference / percentRange));
    }
}
