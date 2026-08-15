package org.ossproject.sonification.port;

import org.ossproject.sonification.model.GraphAudioFrame;

/** Receives asynchronous output events that occur after a frame has been submitted. */
@FunctionalInterface
public interface SonificationOutputListener {
    /**
     * Called when the audio backend cannot play a previously accepted frame.
     *
     * @param frame frame that failed
     * @param error safe output failure
     */
    void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error);

    /**
     * Called when queue saturation discards a frame according to the adapter's policy.
     *
     * @param frame discarded frame
     */
    default void onFrameDropped(GraphAudioFrame frame) {}
}
