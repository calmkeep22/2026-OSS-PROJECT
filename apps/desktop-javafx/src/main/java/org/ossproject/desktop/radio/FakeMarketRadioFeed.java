package org.ossproject.desktop.radio;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class FakeMarketRadioFeed implements AutoCloseable {
    private final String streamKey;
    private final List<Double> values;
    private final Duration interval;
    private final Consumer<TimeSeriesSample> sampleConsumer;
    private final Consumer<RuntimeException> failureConsumer;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "fake-market-radio-feed");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScheduledFuture<?> task;
    private int index;

    public FakeMarketRadioFeed(
            String streamKey,
            List<Double> values,
            Duration interval,
            Consumer<TimeSeriesSample> sampleConsumer,
            Consumer<RuntimeException> failureConsumer
    ) {
        if (streamKey == null || streamKey.isBlank()) throw new IllegalArgumentException("streamKey is required");
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (this.values.size() < 2 || this.values.stream().anyMatch(value ->
                value == null || !Double.isFinite(value) || value <= 0)) {
            throw new IllegalArgumentException("values must contain at least two positive finite numbers");
        }
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.streamKey = streamKey.trim();
        this.sampleConsumer = Objects.requireNonNull(sampleConsumer, "sampleConsumer");
        this.failureConsumer = Objects.requireNonNull(failureConsumer, "failureConsumer");
    }

    public synchronized boolean isRunning() { return task != null && !task.isDone(); }

    public synchronized void start() {
        ensureOpen();
        if (isRunning()) return;
        index = 0;
        task = executor.scheduleAtFixedRate(this::emitNext, interval.toMillis(),
                interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
    }

    private void emitNext() {
        try {
            double value;
            synchronized (this) {
                if (closed.get() || !isRunning()) return;
                value = values.get(index);
                index = (index + 1) % values.size();
            }
            sampleConsumer.accept(new TimeSeriesSample(streamKey, value, Instant.now()));
        } catch (RuntimeException failure) {
            failureConsumer.accept(failure);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("FakeMarketRadioFeed is already closed");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stop();
        executor.shutdownNow();
    }
}
