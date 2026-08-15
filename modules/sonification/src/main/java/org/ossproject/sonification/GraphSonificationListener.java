package org.ossproject.sonification;

import org.ossproject.sonification.model.GraphAudioFrame;

/** Observes mapped frames, queue drops, and synchronous or asynchronous output failures. */
public interface GraphSonificationListener {
    /** @param frame newly mapped graph-audio frame */
    default void onFrameMapped(GraphAudioFrame frame) {}

    /** @param frame mapped frame discarded by the output queue */
    default void onFrameDropped(GraphAudioFrame frame) {}

    /**
     * @param frame frame that could not be played
     * @param error safe output failure
     */
    default void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {}
}
