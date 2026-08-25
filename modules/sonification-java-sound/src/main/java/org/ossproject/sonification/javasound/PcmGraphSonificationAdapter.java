package org.ossproject.sonification.javasound;

import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.port.SonificationOutputListener;
import org.ossproject.sonification.port.SonificationOverflowPolicy;
import org.ossproject.sonification.port.SonificationPort;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Java Sound adapter that renders logarithmic pitch glides as 16-bit mono PCM audio. */
public final class PcmGraphSonificationAdapter implements SonificationPort {
    private static final System.Logger LOGGER = System.getLogger(PcmGraphSonificationAdapter.class.getName());
    private static final float SAMPLE_RATE = 16_000f;

    /**
     * 출력 버퍼 크기.
     *
     * <p>소리가 이만큼 쌓였다가 나가므로, 이 값이 곧 눈과 귀의 시차다. 16kHz · 16비트 ·
     * 모노에서 한 밀리초는 32바이트다. 150밀리초로 잡으면 사람이 어긋남을 느끼기 어려운
     * 범위이면서, 밑돌아 소리가 끊길 만큼 빠듯하지도 않다.
     *
     * <p>더 줄이면 시차는 작아지지만 버퍼가 비어 소리가 끊기기 시작한다. 끊기는 소리는
     * 조금 늦는 소리보다 훨씬 나쁘다 — 이 화면에서 소리는 곧 데이터다.
     */
    private static final int OUTPUT_BUFFER_MILLIS = 150;
    private static final int OUTPUT_BUFFER_BYTES =
            Math.round(SAMPLE_RATE * 2 * OUTPUT_BUFFER_MILLIS / 1_000f);

    /**
     * 버퍼 크기가 곧 지연이다. 화면이 이 값만큼 기다렸다 강조 표시를 옮기면 눈과 귀가
     * 맞는다. 숫자를 화면 쪽에 따로 적어 두지 않는다 — 버퍼를 바꾸면 그 숫자만 남아
     * 어긋난다.
     */
    @Override
    public java.time.Duration outputLatency() {
        return java.time.Duration.ofMillis(OUTPUT_BUFFER_MILLIS);
    }
    private static final int MAX_PENDING_FRAMES = 2;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final CopyOnWriteArrayList<SonificationOutputListener> listeners = new CopyOnWriteArrayList<>();
    private final SourceLineFactory lineFactory;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_FRAMES), runnable -> {
        Thread thread = new Thread(runnable, "graph-sonification");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private volatile SourceDataLine activeLine;
    private volatile double volume = 0.65;
    private double phase;

    /** Creates a PCM adapter that opens the system's default compatible Java Sound output line. */
    public PcmGraphSonificationAdapter() {
        this(AudioSystem::getSourceDataLine);
    }

    PcmGraphSonificationAdapter(SourceLineFactory lineFactory) {
        this.lineFactory = Objects.requireNonNull(lineFactory, "lineFactory");
    }

    @Override public void play(GraphAudioFrame frame) {
        Objects.requireNonNull(frame, "frame");
        ensureOpen();
        FramePlayback playback = new FramePlayback(frame, generation.get());
        try {
            executor.execute(playback);
        } catch (RejectedExecutionException full) {
            if (closed.get()) {
                throw new IllegalStateException("PcmGraphSonificationAdapter is already closed", full);
            }
            Runnable discarded = executor.getQueue().poll();
            if (discarded instanceof FramePlayback dropped) {
                notifyFrameDropped(dropped.frame);
            }
            try {
                executor.execute(playback);
            } catch (RejectedExecutionException rejected) {
                if (closed.get()) {
                    throw new IllegalStateException("PcmGraphSonificationAdapter is already closed", rejected);
                }
                throw new IllegalStateException("Graph sonification frame queue is unavailable", rejected);
            }
        }
    }

    @Override public void setVolume(double volume) {
        ensureOpen();
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) {
            throw new IllegalArgumentException("volume must be between zero and one");
        }
        this.volume = volume;
    }

    @Override public SonificationOverflowPolicy overflowPolicy() {
        return SonificationOverflowPolicy.DROP_OLDEST;
    }

    @Override public void addOutputListener(SonificationOutputListener listener) {
        ensureOpen();
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override public void removeOutputListener(SonificationOutputListener listener) {
        listeners.remove(listener);
    }

    private void playSafely(GraphAudioFrame frame, long requestedGeneration) {
        try {
            if (requestedGeneration != generation.get()) return;
            if (activeLine == null) phase = 0;
            byte[] bytes = render(frame);
            if (requestedGeneration != generation.get()) return;
            SourceDataLine line = activeLine;
            if (line == null || !line.isOpen()) {
                AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
                line = lineFactory.create(format);
                // 버퍼 크기를 지정하지 않으면 Java Sound 가 제 기본값을 쓴다. 그 값은
                // 흔히 0.5초를 넘고, 그만큼 소리가 뒤늦게 나간다. 화면의 강조 표시는
                // 이 write 가 돌아오는 즉시 바뀌므로, 눈이 귀보다 그만큼 앞선다.
                //
                // 지연을 화면 쪽에서 흉내 내 맞추는 방법도 있지만, 그러면 실제로 늦게
                // 나가는 소리는 그대로 두고 눈만 늦추는 셈이다. 소리 자체를 앞당긴다.
                line.open(format, OUTPUT_BUFFER_BYTES);
                activeLine = line;
                if (requestedGeneration != generation.get()) {
                    closeActiveLine();
                    return;
                }
                line.start();
            }
            line.write(bytes, 0, bytes.length);
        } catch (Exception failure) {
            closeActiveLine();
            if (closed.get() || requestedGeneration != generation.get()) return;
            RuntimeException outputFailure = failure instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Graph audio output is unavailable", failure);
            notifyPlaybackFailed(frame, outputFailure);
        }
    }

    private void notifyFrameDropped(GraphAudioFrame frame) {
        for (SonificationOutputListener listener : listeners) {
            try {
                listener.onFrameDropped(frame);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Sonification output listener failed", listenerFailure);
            }
        }
    }

    private void notifyPlaybackFailed(GraphAudioFrame frame, RuntimeException failure) {
        for (SonificationOutputListener listener : listeners) {
            try {
                listener.onPlaybackFailed(frame, failure);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Sonification output listener failed", listenerFailure);
            }
        }
    }

    private byte[] render(GraphAudioFrame frame) {
        int sampleCount = Math.max(1, Math.round(SAMPLE_RATE * frame.duration().toMillis() / 1_000f));
        byte[] bytes = new byte[sampleCount * 2];
        for (int index = 0; index < sampleCount; index++) {
            double progress = sampleCount == 1 ? 1 : index / (double) (sampleCount - 1);
            double frequency = interpolateFrequency(frame, progress);
            phase += 2 * Math.PI * frequency / SAMPLE_RATE;
            if (phase > Math.PI * 2) phase -= Math.PI * 2;
            short sample = (short) (Math.sin(phase) * 4_800 * volume);
            bytes[index * 2] = (byte) sample;
            bytes[index * 2 + 1] = (byte) (sample >>> 8);
        }
        return bytes;
    }

    static double interpolateFrequency(GraphAudioFrame frame, double progress) {
        if (!Double.isFinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException("progress must be between zero and one");
        }
        double start = Math.log(frame.fromFrequencyHz());
        double end = Math.log(frame.toFrequencyHz());
        return Math.exp(start + (end - start) * progress);
    }

    @Override public void stop() {
        generation.incrementAndGet();
        executor.getQueue().clear();
        closeActiveLine();
    }

    private void closeActiveLine() {
        SourceDataLine line = activeLine;
        activeLine = null;
        if (line != null) line.close();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PcmGraphSonificationAdapter is already closed");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop();
        executor.shutdownNow();
        listeners.clear();
    }

    private final class FramePlayback implements Runnable {
        private final GraphAudioFrame frame;
        private final long requestedGeneration;

        private FramePlayback(GraphAudioFrame frame, long requestedGeneration) {
            this.frame = frame;
            this.requestedGeneration = requestedGeneration;
        }

        @Override public void run() {
            playSafely(frame, requestedGeneration);
        }
    }

    @FunctionalInterface
    interface SourceLineFactory {
        SourceDataLine create(AudioFormat format) throws Exception;
    }
}
