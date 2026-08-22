package org.ossproject.sonification.analysis;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.util.List;

/** Reduces a long time series while preserving samples useful for auditory playback. */
@FunctionalInterface
public interface GraphSeriesReducer {
    /** Returns at most {@code maximumPoints} ordered samples from the source series. */
    List<TimeSeriesSample> reduce(List<TimeSeriesSample> samples, int maximumPoints);
}
