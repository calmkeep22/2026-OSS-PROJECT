package org.ossproject.sonification.infrastructure.sound;

import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.port.SonificationOutputListener;
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

    public PcmGraphSonificationAdapter() {
        this(AudioSystem::getSourceDataLine);
    }

    PcmGraphSonificationAdapter(SourceLineFactory lineFactory) {
        this.lineFactory = Objects.requireNonNull(lineFactory, "lineFactory");
    }

    @Override public void play(GraphAudioFrame frame) {
        Objects.requireNonNull(frame, "frame");
        if (closed.get()) throw new IllegalStateException("PcmGraphSonificationAdapter is already closed");
        long requestedGeneration = generation.get();
        Runnable playback = () -> playSafely(frame, requestedGeneration);
        try {
            executor.execute(playback);
        } catch (RejectedExecutionException full) {
            if (closed.get()) throw new IllegalStateException("PcmGraphSonificationAdapter is already closed", full);
            executor.getQueue().poll();
            try {
                executor.execute(playback);
            } catch (RejectedExecutionException rejected) {
                throw new IllegalStateException("Graph sonification frame queue is unavailable", rejected);
            }
        }
    }

    @Override public void setVolume(double volume) {
        if (closed.get()) throw new IllegalStateException("PcmGraphSonificationAdapter is already closed");
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) {
            throw new IllegalArgumentException("volume must be between zero and one");
        }
        this.volume = volume;
    }

    @Override public void addOutputListener(SonificationOutputListener listener) {
        if (closed.get()) throw new IllegalStateException("PcmGraphSonificationAdapter is already closed");
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
                line.open(format);
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
        for (int i = 0; i < sampleCount; i++) {
            double progress = sampleCount == 1 ? 1 : i / (double) (sampleCount - 1);
            double frequency = interpolateFrequency(frame, progress);
            phase += 2 * Math.PI * frequency / SAMPLE_RATE;
            if (phase > Math.PI * 2) phase -= Math.PI * 2;
            short sample = (short) (Math.sin(phase) * 4_800 * volume);
            bytes[i * 2] = (byte) sample;
            bytes[i * 2 + 1] = (byte) (sample >>> 8);
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

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop();
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface SourceLineFactory {
        SourceDataLine create(AudioFormat format) throws Exception;
    }
}
