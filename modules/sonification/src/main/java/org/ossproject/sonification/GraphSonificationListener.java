package org.ossproject.sonification;

import org.ossproject.sonification.model.GraphAudioFrame;

/** Observes mapped frames and synchronous or asynchronous audio output failures. */
public interface GraphSonificationListener {
    default void onFrameMapped(GraphAudioFrame frame) {}
    default void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {}
}
