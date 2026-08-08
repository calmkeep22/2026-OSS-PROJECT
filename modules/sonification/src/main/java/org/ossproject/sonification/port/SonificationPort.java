package org.ossproject.sonification.port;

import org.ossproject.sonification.model.GraphAudioFrame;

/** Output boundary for rendering graph audio independently from the application UI. */
public interface SonificationPort extends AutoCloseable {
    /** Queues one mapped graph frame for playback. */
    void play(GraphAudioFrame frame);

    /** Stops current playback and discards pending frames. */
    void stop();

    /** Sets output volume in the inclusive range {@code 0.0..1.0}. */
    void setVolume(double volume);

    /** Registers an asynchronous output listener when the adapter supports it. */
    default void addOutputListener(SonificationOutputListener listener) {}

    /** Removes a previously registered asynchronous output listener. */
    default void removeOutputListener(SonificationOutputListener listener) {}

    @Override default void close() { stop(); }
}
