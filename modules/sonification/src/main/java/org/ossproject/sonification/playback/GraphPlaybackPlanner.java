package org.ossproject.sonification.playback;

import org.ossproject.sonification.analysis.GraphAnalyzer;
import org.ossproject.sonification.analysis.GraphSeriesReducer;
import org.ossproject.sonification.timing.GraphTimeMapping;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Builds bounded playback plans without discarding the precise source series used for seeking. */
public final class GraphPlaybackPlanner {
    private final GraphSeriesReducer reducer;
    private final GraphTimeMapping timeMapping;

    public GraphPlaybackPlanner(GraphSeriesReducer reducer, GraphTimeMapping timeMapping) {
        this.reducer = Objects.requireNonNull(reducer, "reducer");
        this.timeMapping = Objects.requireNonNull(timeMapping, "timeMapping");
    }

    /** Reduces the audible series and assigns durations totaling the requested playback time. */
    public GraphPlaybackPlan plan(List<TimeSeriesSample> samples, int maximumPoints,
                                  Duration targetDuration) {
        List<TimeSeriesSample> source = GraphAnalyzer.validate(samples);
        List<TimeSeriesSample> playback = reducer.reduce(source, maximumPoints);
        return new GraphPlaybackPlan(source, playback, timeMapping.map(playback, targetDuration));
    }
}
