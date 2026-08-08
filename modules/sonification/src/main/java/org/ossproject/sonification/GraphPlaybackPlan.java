package org.ossproject.sonification;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable playback data that keeps precise source points separate from reduced audible points. */
public final class GraphPlaybackPlan {
    private final List<TimeSeriesSample> sourceSamples;
    private final List<TimeSeriesSample> playbackSamples;
    private final List<Duration> frameDurations;
    private final List<Integer> sourceIndices;

    public GraphPlaybackPlan(List<TimeSeriesSample> sourceSamples,
                             List<TimeSeriesSample> playbackSamples,
                             List<Duration> frameDurations) {
        this.sourceSamples = GraphAnalyzer.validate(sourceSamples);
        this.playbackSamples = GraphAnalyzer.validate(playbackSamples);
        this.frameDurations = List.copyOf(Objects.requireNonNull(frameDurations, "frameDurations"));
        if (this.playbackSamples.size() < 2) {
            throw new IllegalArgumentException("at least two playback samples are required");
        }
        if (this.playbackSamples.size() != this.frameDurations.size()) {
            throw new IllegalArgumentException("one frame duration is required per playback sample");
        }
        if (!this.sourceSamples.get(0).streamKey().equals(this.playbackSamples.get(0).streamKey())) {
            throw new IllegalArgumentException("source and playback samples must use the same streamKey");
        }
        for (Duration duration : this.frameDurations) {
            Objects.requireNonNull(duration, "frameDuration");
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("frame durations must be positive");
            }
        }
        this.sourceIndices = locateSourceIndices(this.sourceSamples, this.playbackSamples);
    }

    /** Creates a plan that plays every source point for the same duration. */
    public static GraphPlaybackPlan uniform(List<TimeSeriesSample> samples, Duration frameDuration) {
        List<TimeSeriesSample> checked = GraphAnalyzer.validate(samples);
        Objects.requireNonNull(frameDuration, "frameDuration");
        return new GraphPlaybackPlan(checked, checked,
                java.util.Collections.nCopies(checked.size(), frameDuration));
    }

    public List<TimeSeriesSample> sourceSamples() { return sourceSamples; }
    public List<TimeSeriesSample> playbackSamples() { return playbackSamples; }
    public List<Duration> frameDurations() { return frameDurations; }
    public int sourceIndexForPlaybackIndex(int playbackIndex) { return sourceIndices.get(playbackIndex); }

    /** Finds the last audible point at or before the specified source index. */
    public int playbackIndexAtOrBeforeSourceIndex(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= sourceSamples.size()) {
            throw new IndexOutOfBoundsException("sourceIndex: " + sourceIndex);
        }
        int result = -1;
        for (int i = 0; i < sourceIndices.size() && sourceIndices.get(i) <= sourceIndex; i++) result = i;
        return result;
    }

    private static List<Integer> locateSourceIndices(List<TimeSeriesSample> source,
                                                      List<TimeSeriesSample> playback) {
        List<Integer> indices = new ArrayList<>(playback.size());
        int searchFrom = 0;
        for (TimeSeriesSample target : playback) {
            int found = -1;
            for (int i = searchFrom; i < source.size(); i++) {
                if (source.get(i).equals(target)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                throw new IllegalArgumentException("playback samples must be an ordered subset of source samples");
            }
            indices.add(found);
            searchFrom = found + 1;
        }
        return List.copyOf(indices);
    }
}
