package org.ossproject.accessibility.infrastructure.sound;

import org.ossproject.accessibility.notification.SoundCue;
import org.ossproject.accessibility.port.SoundPort;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ToneSoundAdapter implements SoundPort {
    private static final float SAMPLE_RATE = 16_000f;
    private static final int MAX_PENDING_SOUNDS = 8;
    private final Set<SoundCue> scheduled = EnumSet.noneOf(SoundCue.class);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_SOUNDS), runnable -> {
        Thread thread = new Thread(runnable, "sonification"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private volatile SourceDataLine activeLine;
    private volatile double volume = 1.0;

    @Override public void play(SoundCue cue) {
        Objects.requireNonNull(cue, "cue");
        if (closed.get()) throw new IllegalStateException("ToneSoundAdapter is already closed");
        synchronized (scheduled) {
            if (!scheduled.add(cue)) return;
        }
        try {
            executor.execute(() -> {
                try { playPattern(cue); }
                finally { synchronized (scheduled) { scheduled.remove(cue); } }
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (scheduled) { scheduled.remove(cue); }
            if (closed.get()) throw new IllegalStateException("ToneSoundAdapter is already closed", rejected);
        }
    }

    @Override public void setVolume(double volume) {
        if (closed.get()) throw new IllegalStateException("ToneSoundAdapter is already closed");
        if (volume < 0 || volume > 1) throw new IllegalArgumentException("volume must be between 0 and 1");
        this.volume = volume;
    }

    private void playPattern(SoundCue cue) {
        try {
            switch (cue) {
                case SUCCESS, CONNECTION_RESTORED, ORDER_FILLED -> { tone(660, 90); tone(880, 130); }
                case WARNING -> { tone(440, 130); silence(60); tone(440, 130); }
                case ERROR, CONNECTION_LOST, ORDER_REJECTED -> { tone(300, 180); silence(50); tone(220, 220); }
                case ANOMALY_HIGH -> { tone(880, 100); silence(45); tone(880, 100); silence(45); tone(660, 150); }
            }
        } catch (Exception ignored) {
            // The same state must remain available as visible text and speech.
        }
    }

    private void tone(double frequency, int milliseconds) throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        int sampleCount = Math.round(SAMPLE_RATE * milliseconds / 1000f);
        byte[] bytes = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            double envelope = Math.min(1, i / 160d) * Math.min(1, (sampleCount - i) / 160d);
            short sample = (short) (Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE)
                    * 6_000 * envelope * volume);
            bytes[i * 2] = (byte) sample;
            bytes[i * 2 + 1] = (byte) (sample >>> 8);
        }
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        activeLine = line;
        try (line) {
            line.open(format); line.start(); line.write(bytes, 0, bytes.length); line.drain();
        } finally {
            activeLine = null;
        }
    }

    private void silence(int milliseconds) throws InterruptedException { Thread.sleep(milliseconds); }

    @Override public void stop() {
        executor.getQueue().clear();
        synchronized (scheduled) { scheduled.clear(); }
        SourceDataLine line = activeLine;
        if (line != null) line.close();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop();
        executor.shutdownNow();
    }
}
