package org.ossproject.sonification;

import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.model.GraphValueScale;
import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Thread-safe transport controller for historical graph playback and exact source-point seeking. */
public final class GraphPlaybackController implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(GraphPlaybackController.class.getName());
    private static final Duration POINT_PREVIEW_DURATION = Duration.ofMillis(300);
    private final Object stateLock = new Object();
    private final StreamingGraphSonifier sonifier;
    private final List<GraphPlaybackListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private GraphPlaybackPlan plan;
    private GraphValueScale scale;
    private GraphPlaybackState state = GraphPlaybackState.EMPTY;
    private ScheduledFuture<?> scheduled;
    private int currentIndex = -1;
    private int playbackIndex = -1;
    private double speed = 1.0;

    public GraphPlaybackController(StreamingGraphSonifier sonifier) {
        this(sonifier, createDefaultExecutor());
    }

    /**
     * Creates a controller with an injected scheduler. The controller owns the scheduler and
     * shuts it down when {@link #close()} is called.
     */
    public GraphPlaybackController(StreamingGraphSonifier sonifier, ScheduledExecutorService executor) {
        this.sonifier = Objects.requireNonNull(sonifier, "sonifier");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    private static ScheduledExecutorService createDefaultExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "graph-playback");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void addListener(GraphPlaybackListener listener) {
        ensureOpen();
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(GraphPlaybackListener listener) { listeners.remove(listener); }

    public void load(List<TimeSeriesSample> samples, GraphValueScale scale, Duration frameDuration) {
        load(GraphPlaybackPlan.uniform(samples, frameDuration), scale);
    }

    /** Loads a plan that may use reduced playback points while preserving all seekable points. */
    public void load(GraphPlaybackPlan plan, GraphValueScale scale) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scale, "scale");
        synchronized (stateLock) {
            ensureOpen();
            cancelScheduledLocked();
            sonifier.stop();
            this.plan = plan;
            this.scale = scale;
            currentIndex = -1;
            playbackIndex = -1;
            state = GraphPlaybackState.READY;
        }
        notifyListeners(listener -> listener.onStateChanged(GraphPlaybackState.READY));
    }

    public void play() {
        GraphPlaybackState changed;
        synchronized (stateLock) {
            ensureReady();
            if (state == GraphPlaybackState.PLAYING) return;
            if (state == GraphPlaybackState.COMPLETED) {
                currentIndex = -1;
                playbackIndex = -1;
            }
            prepareSonifierLocked();
            state = GraphPlaybackState.PLAYING;
            scheduleNextLocked(Duration.ZERO);
            changed = state;
        }
        notifyListeners(listener -> listener.onStateChanged(changed));
    }

    public void pause() {
        synchronized (stateLock) {
            ensureOpen();
            if (state != GraphPlaybackState.PLAYING) return;
            cancelScheduledLocked();
            sonifier.stop();
            state = GraphPlaybackState.PAUSED;
        }
        notifyListeners(listener -> listener.onStateChanged(GraphPlaybackState.PAUSED));
    }

    public void stop() {
        GraphPlaybackState changed;
        synchronized (stateLock) {
            if (closed.get()) return;
            cancelScheduledLocked();
            sonifier.stop();
            currentIndex = -1;
            playbackIndex = -1;
            state = plan == null ? GraphPlaybackState.EMPTY : GraphPlaybackState.READY;
            changed = state;
        }
        notifyListeners(listener -> listener.onStateChanged(changed));
    }

    public GraphAudioFrame seek(int index) {
        GraphAudioFrame frame;
        TimeSeriesSample sample;
        synchronized (stateLock) {
            ensureReady();
            if (index < 0 || index >= plan.sourceSamples().size()) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            cancelScheduledLocked();
            sonifier.stop();
            sample = plan.sourceSamples().get(index);
            sonifier.startAt(sample.streamKey(), scale, sample.value());
            frame = sonifier.accept(sample, POINT_PREVIEW_DURATION).orElseThrow();
            currentIndex = index;
            playbackIndex = plan.playbackIndexAtOrBeforeSourceIndex(index);
            state = GraphPlaybackState.PAUSED;
        }
        GraphAudioFrame selectedFrame = frame;
        TimeSeriesSample selectedSample = sample;
        notifyListeners(listener -> listener.onStateChanged(GraphPlaybackState.PAUSED));
        int total = size();
        notifyListeners(listener -> listener.onPointChanged(index, total, selectedSample, selectedFrame));
        return frame;
    }

    public void replay() {
        stop();
        play();
    }

    public void setSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0.5 || speed > 4.0) {
            throw new IllegalArgumentException("speed must be between 0.5 and 4.0");
        }
        synchronized (stateLock) {
            ensureOpen();
            this.speed = speed;
            if (state == GraphPlaybackState.PLAYING) {
                cancelScheduledLocked();
                sonifier.stop();
                prepareSonifierLocked();
                scheduleNextLocked(Duration.ZERO);
            }
        }
    }

    public double speed() { synchronized (stateLock) { return speed; } }
    public int currentIndex() { synchronized (stateLock) { return currentIndex; } }
    public int size() { synchronized (stateLock) { return plan == null ? 0 : plan.sourceSamples().size(); } }
    public GraphPlaybackState state() { synchronized (stateLock) { return state; } }

    public Optional<TimeSeriesSample> currentSample() {
        synchronized (stateLock) {
            return currentIndex < 0 ? Optional.empty() : Optional.of(plan.sourceSamples().get(currentIndex));
        }
    }

    private void emitNext() {
        GraphAudioFrame frame = null;
        TimeSeriesSample sample = null;
        int index = -1;
        int total = 0;
        boolean completed = false;
        RuntimeException failure = null;
        synchronized (stateLock) {
            if (closed.get() || state != GraphPlaybackState.PLAYING) return;
            int nextPlaybackIndex = playbackIndex + 1;
            if (nextPlaybackIndex >= plan.playbackSamples().size()) {
                scheduled = null;
                state = GraphPlaybackState.COMPLETED;
                completed = true;
            } else {
                try {
                    Duration duration = effectiveFrameDurationLocked(
                            plan.frameDurations().get(nextPlaybackIndex));
                    sample = plan.playbackSamples().get(nextPlaybackIndex);
                    frame = sonifier.accept(sample, duration).orElseThrow();
                    playbackIndex = nextPlaybackIndex;
                    currentIndex = plan.sourceIndexForPlaybackIndex(nextPlaybackIndex);
                    index = currentIndex;
                    total = plan.sourceSamples().size();
                    scheduleNextLocked(duration);
                } catch (RuntimeException error) {
                    scheduled = null;
                    sonifier.stop();
                    state = GraphPlaybackState.PAUSED;
                    failure = error;
                }
            }
        }
        if (frame != null) {
            GraphAudioFrame emittedFrame = frame;
            TimeSeriesSample emittedSample = sample;
            int emittedIndex = index;
            int emittedTotal = total;
            notifyListeners(listener -> listener.onPointChanged(emittedIndex, emittedTotal, emittedSample, emittedFrame));
        }
        if (completed) notifyListeners(listener -> listener.onStateChanged(GraphPlaybackState.COMPLETED));
        if (failure != null) {
            RuntimeException emittedFailure = failure;
            notifyListeners(listener -> listener.onPlaybackFailed(emittedFailure));
            notifyListeners(listener -> listener.onStateChanged(GraphPlaybackState.PAUSED));
        }
    }

    private void prepareSonifierLocked() {
        cancelScheduledLocked();
        sonifier.stop();
        TimeSeriesSample anchor = currentIndex >= 0
                ? plan.sourceSamples().get(currentIndex)
                : plan.playbackSamples().get(0);
        sonifier.startAt(anchor.streamKey(), scale, anchor.value());
    }

    private Duration effectiveFrameDurationLocked(Duration baseFrameDuration) {
        long nanos = Math.max(1_000_000L, Math.round(baseFrameDuration.toNanos() / speed));
        return Duration.ofNanos(nanos);
    }

    private void scheduleNextLocked(Duration delay) {
        scheduled = executor.schedule(this::emitNext, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
    }

    private void ensureReady() {
        ensureOpen();
        if (plan == null) throw new IllegalStateException("graph data has not been loaded");
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("GraphPlaybackController is already closed");
    }

    private void notifyListeners(Consumer<GraphPlaybackListener> action) {
        for (GraphPlaybackListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Graph playback listener failed", listenerFailure);
            }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (stateLock) {
            cancelScheduledLocked();
            sonifier.stop();
            plan = null;
            state = GraphPlaybackState.EMPTY;
        }
        executor.shutdownNow();
    }
}
