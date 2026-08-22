package org.ossproject.sonification.playback;

/** Lifecycle states of a historical graph playback controller. */
public enum GraphPlaybackState {
    EMPTY,
    READY,
    PLAYING,
    PAUSED,
    COMPLETED
}
