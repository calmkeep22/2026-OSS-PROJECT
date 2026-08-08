package org.ossproject.sonification;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.util.List;

/** Maps source timestamps to the audible duration assigned to each playback point. */
@FunctionalInterface
public interface GraphTimeMapping {
    /** Returns one positive duration per sample whose total approximates {@code targetDuration}. */
    List<Duration> map(List<TimeSeriesSample> samples, Duration targetDuration);
}
