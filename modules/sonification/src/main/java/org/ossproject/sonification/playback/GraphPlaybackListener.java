package org.ossproject.sonification.playback;

import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.model.TimeSeriesSample;

/** Observes playback state, current source-point selection, and controller failures. */
public interface GraphPlaybackListener {
    default void onStateChanged(GraphPlaybackState state) {}
    default void onPointChanged(int index, int total, TimeSeriesSample sample, GraphAudioFrame frame) {}
    default void onPlaybackFailed(RuntimeException error) {}
}
