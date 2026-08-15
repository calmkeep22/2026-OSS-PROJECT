package org.ossproject.sonification.port;

import org.ossproject.sonification.model.GraphAudioFrame;

/**
 * Output boundary for rendering graph audio independently from the application UI.
 *
 * <p>A port is an exclusive playback sink. A mapper may borrow it and call {@link #stop()}, but
 * the composition root that created the port owns its lifetime and must call {@link #close()}.
 * A single port must not be shared by concurrently playing sonifiers because {@code stop()} clears
 * the whole output queue.</p>
 */
public interface SonificationPort extends AutoCloseable {
    /**
     * Queues one mapped graph frame for playback without waiting for its audible duration.
     * Implementations must reject {@code null} and apply {@link #overflowPolicy()} when saturated.
     *
     * @param frame validated graph-audio frame to submit
     */
    void play(GraphAudioFrame frame);

    /** Stops current playback and discards pending frames. Safe to call repeatedly and after close. */
    void stop();

    /**
     * Sets output volume in the inclusive range {@code 0.0..1.0}.
     * Invalid values must raise {@link IllegalArgumentException}.
     *
     * @param volume normalized output gain
     */
    void setVolume(double volume);

    /**
     * Reports the adapter's explicit queue-saturation behavior.
     *
     * @return non-null overflow policy
     */
    SonificationOverflowPolicy overflowPolicy();

    /**
     * Registers an asynchronous output listener when the adapter supports it.
     *
     * @param listener listener to register
     */
    default void addOutputListener(SonificationOutputListener listener) {}

    /**
     * Removes a previously registered asynchronous output listener.
     *
     * @param listener listener to remove
     */
    default void removeOutputListener(SonificationOutputListener listener) {}

    /**
     * Releases output resources. Closing must be idempotent; new playback, volume, and listener
     * registration requests must fail with {@link IllegalStateException} after close.
     */
    @Override void close();
}
