package org.ossproject.sonification;

import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.model.GraphSonificationConfig;
import org.ossproject.sonification.model.GraphValueScale;
import org.ossproject.sonification.model.TimeSeriesSample;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.sonification.port.SonificationOutputListener;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Stateful mapper that turns ordered time-series values into continuous pitch frames. */
public final class StreamingGraphSonifier implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(StreamingGraphSonifier.class.getName());
    private final Object stateLock = new Object();
    private final List<GraphSonificationListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SonificationPort sonificationPort;
    private final GraphSonificationConfig config;
    private final SonificationOutputListener outputListener = new SonificationOutputListener() {
        @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {
            handleOutputFailure(frame, error);
        }

        @Override public void onFrameDropped(GraphAudioFrame frame) {
            handleFrameDropped(frame);
        }
    };
    private boolean running;
    private String streamKey;
    private GraphValueScale scale;
    private double previousFrequency;
    private Instant previousTimestamp;

    /**
     * Borrows an exclusive output port; the caller remains responsible for closing the port.
     *
     * @param sonificationPort exclusive output port
     */
    public StreamingGraphSonifier(SonificationPort sonificationPort) {
        this(sonificationPort, GraphSonificationConfig.DEFAULT);
    }

    /**
     * Borrows an exclusive output port; the caller remains responsible for closing the port.
     *
     * @param sonificationPort exclusive output port
     * @param config graph-to-frequency mapping configuration
     */
    public StreamingGraphSonifier(SonificationPort sonificationPort, GraphSonificationConfig config) {
        this.sonificationPort = Objects.requireNonNull(sonificationPort, "sonificationPort");
        this.config = Objects.requireNonNull(config, "config");
        this.sonificationPort.addOutputListener(outputListener);
    }

    public GraphSonificationConfig config() { return config; }

    public boolean isRunning() {
        synchronized (stateLock) { return running; }
    }

    public void addListener(GraphSonificationListener listener) {
        GraphSonificationListener checked = Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            ensureOpen();
            listeners.add(checked);
        }
    }

    public void removeListener(GraphSonificationListener listener) { listeners.remove(listener); }

    public void start(String streamKey) {
        startInternal(streamKey, null, config.centerFrequencyHz());
    }

    public void start(String streamKey, GraphValueScale scale) {
        Objects.requireNonNull(scale, "scale");
        startInternal(streamKey, scale, config.centerFrequencyHz());
    }

    public void startAt(String streamKey, GraphValueScale scale, double initialValue) {
        Objects.requireNonNull(scale, "scale");
        if (!Double.isFinite(initialValue) || initialValue <= 0) {
            throw new IllegalArgumentException("initialValue must be finite and positive");
        }
        startInternal(streamKey, scale,
                config.frequencyForNormalizedPosition(scale.normalizedPosition(initialValue)));
    }

    private void startInternal(String streamKey, GraphValueScale scale, double initialFrequency) {
        if (streamKey == null || streamKey.isBlank()) throw new IllegalArgumentException("streamKey is required");
        synchronized (stateLock) {
            ensureOpen();
            this.streamKey = streamKey.trim();
            this.scale = scale;
            previousFrequency = initialFrequency;
            previousTimestamp = null;
            running = true;
        }
    }

    public void stop() {
        synchronized (stateLock) {
            if (closed.get()) return;
            running = false;
            streamKey = null;
            scale = null;
            previousTimestamp = null;
        }
        sonificationPort.stop();
    }

    public Optional<GraphAudioFrame> accept(TimeSeriesSample sample) {
        return accept(sample, config.frameDuration());
    }

    public Optional<GraphAudioFrame> accept(TimeSeriesSample sample, java.time.Duration frameDuration) {
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(frameDuration, "frameDuration");
        if (frameDuration.isZero() || frameDuration.isNegative()) {
            throw new IllegalArgumentException("frameDuration must be positive");
        }
        GraphAudioFrame frame;
        synchronized (stateLock) {
            ensureOpen();
            if (!running) return Optional.empty();
            if (!streamKey.equals(sample.streamKey())) {
                throw new IllegalArgumentException("sample streamKey does not match the active graph");
            }
            if (previousTimestamp != null && sample.timestamp().isBefore(previousTimestamp)) {
                throw new IllegalArgumentException("samples must be ordered by timestamp");
            }
            if (scale == null) scale = GraphValueScale.percentFromReference(sample.value(), config.percentRange());
            double percent = scale.percentFromReference(sample.value());
            double normalizedPosition = scale.normalizedPosition(sample.value());
            double targetFrequency = config.frequencyForNormalizedPosition(normalizedPosition);
            frame = new GraphAudioFrame(streamKey, previousFrequency, targetFrequency,
                    percent, normalizedPosition, sample.value(), frameDuration, sample.timestamp());
            previousFrequency = targetFrequency;
            previousTimestamp = sample.timestamp();
        }

        notifyListeners(listener -> listener.onFrameMapped(frame));
        try {
            sonificationPort.play(frame);
        } catch (RuntimeException failure) {
            notifyListeners(listener -> listener.onPlaybackFailed(frame, failure));
        }
        return Optional.of(frame);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("StreamingGraphSonifier is already closed");
    }

    private void handleOutputFailure(GraphAudioFrame frame, RuntimeException error) {
        if (!closed.get()) notifyListeners(listener -> listener.onPlaybackFailed(frame, error));
    }

    private void handleFrameDropped(GraphAudioFrame frame) {
        if (!closed.get()) notifyListeners(listener -> listener.onFrameDropped(frame));
    }

    private void notifyListeners(Consumer<GraphSonificationListener> action) {
        for (GraphSonificationListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Graph sonification listener failed", listenerFailure);
            }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (stateLock) {
            running = false;
            streamKey = null;
            scale = null;
            previousTimestamp = null;
        }
        sonificationPort.removeOutputListener(outputListener);
        sonificationPort.stop();
    }
}
