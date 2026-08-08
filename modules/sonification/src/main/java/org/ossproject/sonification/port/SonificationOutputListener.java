package org.ossproject.sonification.port;

import org.ossproject.sonification.model.GraphAudioFrame;

/** Receives failures that occur after an audio frame has been queued for playback. */
@FunctionalInterface
public interface SonificationOutputListener {
    /** Called when the audio backend cannot play a previously accepted frame. */
    void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error);
}
