package org.ossproject.desktop.state;

import org.ossproject.sonification.model.GraphScaleMode;

import java.util.Objects;

/** 청각 차트의 사용자 조절값. 오디오 매핑 알고리즘은 sonification 모듈에 둔다. */
public record SonificationPreferences(
        GraphScaleMode scaleMode,
        double percentRange,
        double playbackSpeed,
        double volume
) {
    public static final SonificationPreferences DEFAULT = new SonificationPreferences(
            GraphScaleMode.AUTOMATIC, 5.0, 1.0, 0.8);

    public SonificationPreferences {
        scaleMode = Objects.requireNonNullElse(scaleMode, GraphScaleMode.AUTOMATIC);
        percentRange = finiteRange(percentRange, 0.1, 99.9, 5.0);
        playbackSpeed = finiteRange(playbackSpeed, 0.5, 4.0, 1.0);
        volume = finiteRange(volume, 0.0, 1.0, 0.8);
    }

    private static double finiteRange(double value, double minimum, double maximum, double fallback) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
