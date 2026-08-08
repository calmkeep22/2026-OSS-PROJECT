package org.ossproject.accessibility.notification;

import org.ossproject.accessibility.port.SpeechPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class SpeechQueue implements AutoCloseable {
    private static final int PROTECTED_PRIORITY = SpeechPriority.ORDER.weight();

    private static final class QueuedSpeech {
        private final SpeechRequest request;
        private final long sequence;
        private final AtomicBoolean interrupted = new AtomicBoolean();

        private QueuedSpeech(SpeechRequest request, long sequence) {
            this.request = request;
            this.sequence = sequence;
        }
    }

    private final PriorityQueue<QueuedSpeech> queue = new PriorityQueue<>(16,
            Comparator.<QueuedSpeech>comparingInt(item -> item.request.priority().weight()).reversed()
                    .thenComparingLong(item -> item.sequence));
    private final Map<String, QueuedSpeech> pendingByKey = new HashMap<>();
    private final List<SpeechListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object stateLock = new Object();
    private final SpeechPort speechPort;
    private final SpeechQueueConfig config;
    private final Thread worker;
    private volatile SpeechOptions options;
    private QueuedSpeech currentSpeech;

    public SpeechQueue(SpeechPort speechPort) {
        this(speechPort, SpeechOptions.DEFAULT, SpeechQueueConfig.DEFAULT);
    }

    public SpeechQueue(SpeechPort speechPort, SpeechOptions options) {
        this(speechPort, options, SpeechQueueConfig.DEFAULT);
    }

    public SpeechQueue(SpeechPort speechPort, SpeechOptions options, SpeechQueueConfig config) {
        this.speechPort = Objects.requireNonNull(speechPort, "speechPort");
        this.config = Objects.requireNonNull(config, "config");
        this.options = Objects.requireNonNull(options, "options");
        speechPort.applyOptions(options);
        worker = new Thread(this::runWorker, "speech-queue");
        worker.setDaemon(true);
        worker.start();
    }

    public SpeechOptions options() { return options; }
    public int pendingCount() {
        synchronized (stateLock) { return queue.size(); }
    }
    public boolean isClosed() { return !running.get(); }

    public void setOptions(SpeechOptions options) {
        SpeechOptions checked = Objects.requireNonNull(options, "options");
        synchronized (stateLock) {
            ensureOpen();
            this.options = checked;
            speechPort.applyOptions(checked);
        }
    }

    public void addListener(SpeechListener listener) {
        SpeechListener checked = Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            ensureOpen();
            listeners.add(checked);
        }
    }

    public void removeListener(SpeechListener listener) { listeners.remove(listener); }

    public boolean announce(SpeechRequest request) {
        Objects.requireNonNull(request, "request");
        List<QueuedSpeech> interruptedPending = new ArrayList<>();
        QueuedSpeech preempted = null;
        boolean accepted = false;
        synchronized (stateLock) {
            ensureOpen();
            if (applyMergePolicy(request, interruptedPending)
                    && makeRoomFor(request, interruptedPending)) {
                QueuedSpeech item = new QueuedSpeech(request, sequence.getAndIncrement());
                queue.add(item);
                if (request.mergePolicy() != SpeechMergePolicy.ALLOW_ALL) {
                    pendingByKey.put(request.deduplicationKey(), item);
                }
                stateLock.notifyAll();
                if (currentSpeech != null
                        && request.priority().weight() > currentSpeech.request.priority().weight()) {
                    currentSpeech.interrupted.set(true);
                    preempted = currentSpeech;
                }
                accepted = true;
            }
        }
        interruptedPending.forEach(item ->
                notifyListeners(listener -> listener.onInterrupted(item.request)));
        if (preempted != null) speechPort.stop();
        return accepted;
    }

    private boolean applyMergePolicy(SpeechRequest request, List<QueuedSpeech> interruptedPending) {
        if (request.mergePolicy() == SpeechMergePolicy.ALLOW_ALL) return true;
        QueuedSpeech pending = pendingByKey.get(request.deduplicationKey());
        boolean currentHasKey = currentSpeech != null
                && currentSpeech.request.deduplicationKey().equals(request.deduplicationKey());
        if (request.mergePolicy() == SpeechMergePolicy.KEEP_FIRST) {
            return pending == null && !currentHasKey;
        }
        if (pending != null) {
            queue.remove(pending);
            pending.interrupted.set(true);
            pendingByKey.remove(request.deduplicationKey(), pending);
            interruptedPending.add(pending);
        }
        return true;
    }

    private boolean makeRoomFor(SpeechRequest incoming, List<QueuedSpeech> interruptedPending) {
        if (queue.size() < config.maxPendingRequests()) return true;
        QueuedSpeech removable = queue.stream()
                .filter(item -> item.request.priority().weight() < PROTECTED_PRIORITY)
                .filter(item -> item.request.priority().weight() <= incoming.priority().weight())
                .min(Comparator.<QueuedSpeech>comparingInt(item -> item.request.priority().weight())
                        .thenComparingLong(item -> item.sequence))
                .orElse(null);
        if (removable == null) return false;
        queue.remove(removable);
        if (removable.request.mergePolicy() != SpeechMergePolicy.ALLOW_ALL) {
            pendingByKey.remove(removable.request.deduplicationKey(), removable);
        }
        removable.interrupted.set(true);
        interruptedPending.add(removable);
        return true;
    }

    public void clear() {
        List<QueuedSpeech> removed;
        QueuedSpeech active;
        synchronized (stateLock) {
            ensureOpen();
            removed = new ArrayList<>(queue);
            queue.clear();
            pendingByKey.clear();
            removed.forEach(item -> item.interrupted.set(true));
            active = currentSpeech;
            if (active != null) {
                active.interrupted.set(true);
                currentSpeech = null;
            }
        }
        removed.forEach(item -> notifyListeners(listener -> listener.onInterrupted(item.request)));
        if (active != null) speechPort.stop();
    }

    private void runWorker() {
        while (true) {
            QueuedSpeech item;
            synchronized (stateLock) {
                while (running.get() && queue.isEmpty()) {
                    try {
                        stateLock.wait();
                    } catch (InterruptedException interrupted) {
                        if (!running.get()) return;
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running.get()) return;
                item = queue.remove();
                if (item.request.mergePolicy() != SpeechMergePolicy.ALLOW_ALL) {
                    pendingByKey.remove(item.request.deduplicationKey(), item);
                }
                currentSpeech = item;
            }

            notifyListeners(listener -> listener.onStarted(item.request));
            if (item.interrupted.get() || !running.get()) {
                notifyListeners(listener -> listener.onInterrupted(item.request));
                synchronized (stateLock) {
                    if (currentSpeech == item) currentSpeech = null;
                }
                if (!running.get()) return;
                continue;
            }
            try {
                speechPort.speak(item.request.text());
                if (item.interrupted.get()) notifyListeners(listener -> listener.onInterrupted(item.request));
                else notifyListeners(listener -> listener.onCompleted(item.request));
            } catch (InterruptedException interrupted) {
                item.interrupted.set(true);
                notifyListeners(listener -> listener.onInterrupted(item.request));
                if (!running.get()) return;
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                if (item.interrupted.get()) notifyListeners(listener -> listener.onInterrupted(item.request));
                else notifyListeners(listener -> listener.onFailed(item.request, failure));
            } finally {
                synchronized (stateLock) {
                    if (currentSpeech == item) currentSpeech = null;
                }
            }
        }
    }

    private void ensureOpen() {
        if (!running.get()) throw new IllegalStateException("SpeechQueue is already closed");
    }

    private void notifyListeners(Consumer<SpeechListener> action) {
        for (SpeechListener listener : listeners) {
            try { action.accept(listener); } catch (RuntimeException ignored) {}
        }
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        List<QueuedSpeech> removed;
        QueuedSpeech active;
        synchronized (stateLock) {
            removed = new ArrayList<>(queue);
            queue.clear();
            pendingByKey.clear();
            removed.forEach(item -> item.interrupted.set(true));
            active = currentSpeech;
            if (active != null) {
                active.interrupted.set(true);
                currentSpeech = null;
            }
            stateLock.notifyAll();
        }
        removed.forEach(item -> notifyListeners(listener -> listener.onInterrupted(item.request)));
        speechPort.close();
        worker.interrupt();
    }
}
