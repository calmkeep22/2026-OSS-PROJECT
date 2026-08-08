package org.ossproject.sonification;

/** Lifecycle states of a historical graph playback controller. */
public enum GraphPlaybackState {
    EMPTY,
    READY,
    PLAYING,
    PAUSED,
    COMPLETED
}
