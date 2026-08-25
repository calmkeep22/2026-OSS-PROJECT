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
     * 소리가 실제로 귀에 닿기까지 걸리는 시간.
     *
     * <p>출력 장치는 소리를 버퍼에 쌓았다가 내보낸다. 그래서 프레임을 넘긴 시각과
     * 들리는 시각이 다르다. 화면이 그 차이를 모르면 강조 표시가 소리보다 앞서 움직인다.
     *
     * <p>기본값은 0이다. 소리를 내지 않는 구현이나 검사용 구현은 기다릴 것이 없다.
     *
     * @return 프레임을 넘긴 뒤 들리기까지의 시간
     */
    default java.time.Duration outputLatency() {
        return java.time.Duration.ZERO;
    }

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
