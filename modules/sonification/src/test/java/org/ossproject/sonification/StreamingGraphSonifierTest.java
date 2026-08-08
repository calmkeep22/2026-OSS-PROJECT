package org.ossproject.sonification;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.model.*;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.sonification.port.SonificationOutputListener;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamingGraphSonifierTest {
    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test void mapsThePriceGraphToContinuousPitchFrames() {
        RecordingPort port = new RecordingPort();
        try (StreamingGraphSonifier graph = new StreamingGraphSonifier(port)) {
            graph.start("005930");

            GraphAudioFrame reference = graph.accept(sample(0, 100)).orElseThrow();
            assertEquals(440, reference.fromFrequencyHz(), 0.001);
            assertEquals(440, reference.toFrequencyHz(), 0.001);
            assertEquals(0, reference.percentFromReference(), 0.001);

            GraphAudioFrame rise = graph.accept(sample(1, 102.5)).orElseThrow();
            assertEquals(440, rise.fromFrequencyHz(), 0.001);
            assertEquals(Math.sqrt(440 * 880), rise.toFrequencyHz(), 0.001);
            assertEquals(0.5, rise.normalizedPosition(), 0.001);

            GraphAudioFrame fall = graph.accept(sample(2, 95)).orElseThrow();
            assertEquals(rise.toFrequencyHz(), fall.fromFrequencyHz(), 0.001);
            assertEquals(220, fall.toFrequencyHz(), 0.001);
            assertEquals(List.of(reference, rise, fall), port.frames);
        }
    }

    @Test void clampsValuesOutsideTheConfiguredAudibleRange() {
        RecordingPort port = new RecordingPort();
        try (StreamingGraphSonifier graph = new StreamingGraphSonifier(port)) {
            graph.start("005930");
            graph.accept(sample(0, 100));
            assertEquals(880, graph.accept(sample(1, 120)).orElseThrow().toFrequencyHz(), 0.001);
            assertEquals(220, graph.accept(sample(2, 80)).orElseThrow().toFrequencyHz(), 0.001);
        }
    }

    @Test void usesAnExplicitAutomaticScaleAndCustomFrameDuration() {
        RecordingPort port = new RecordingPort();
        List<TimeSeriesSample> samples = List.of(sample(0, 100), sample(1, 105), sample(2, 110));
        try (StreamingGraphSonifier graph = new StreamingGraphSonifier(port)) {
            GraphValueScale scale = GraphValueScale.automatic(samples);
            graph.startAt("005930", scale, 100);
            GraphAudioFrame low = graph.accept(samples.get(0), java.time.Duration.ofMillis(250)).orElseThrow();
            GraphAudioFrame middle = graph.accept(samples.get(1), java.time.Duration.ofMillis(250)).orElseThrow();
            GraphAudioFrame high = graph.accept(samples.get(2), java.time.Duration.ofMillis(250)).orElseThrow();
            assertEquals(220, low.toFrequencyHz(), 0.001);
            assertEquals(440, middle.toFrequencyHz(), 0.001);
            assertEquals(880, high.toFrequencyHz(), 0.001);
            assertEquals(java.time.Duration.ofMillis(250), high.duration());
        }
    }

    @Test void validatesStreamOrderingAndLifecycle() {
        RecordingPort port = new RecordingPort();
        StreamingGraphSonifier graph = new StreamingGraphSonifier(port);
        assertTrue(graph.accept(sample(0, 100)).isEmpty());
        graph.start("005930");
        graph.accept(sample(5, 100));
        assertThrows(IllegalArgumentException.class, () -> graph.accept(sample(4, 101)));
        assertThrows(IllegalArgumentException.class, () -> graph.accept(
                new TimeSeriesSample("000660", 100, START.plusSeconds(6))));
        graph.stop();
        assertFalse(graph.isRunning());
        assertTrue(graph.accept(sample(6, 101)).isEmpty());
        graph.close();
        assertTrue(port.closed);
        assertThrows(IllegalStateException.class, () -> graph.start("005930"));
        assertThrows(IllegalStateException.class, () -> graph.accept(sample(7, 102)));
        assertDoesNotThrow(graph::close);
    }

    @Test void reportsPlaybackFailureWithoutLosingTheMappedFrame() {
        SonificationPort failing = new SonificationPort() {
            @Override public void play(GraphAudioFrame frame) { throw new IllegalStateException("audio unavailable"); }
            @Override public void stop() {}
            @Override public void setVolume(double volume) {}
        };
        AtomicInteger mapped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try (StreamingGraphSonifier graph = new StreamingGraphSonifier(failing)) {
            graph.addListener(new GraphSonificationListener() {
                @Override public void onFrameMapped(GraphAudioFrame frame) { mapped.incrementAndGet(); }
                @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) { failed.incrementAndGet(); }
            });
            graph.start("005930");
            Optional<GraphAudioFrame> result = graph.accept(sample(0, 100));
            assertTrue(result.isPresent());
            assertEquals(1, mapped.get());
            assertEquals(1, failed.get());
        }
    }

    @Test void forwardsFailuresReportedAfterAFrameWasAccepted() {
        AsyncFailingPort port = new AsyncFailingPort();
        AtomicInteger failed = new AtomicInteger();
        try (StreamingGraphSonifier graph = new StreamingGraphSonifier(port)) {
            graph.addListener(new GraphSonificationListener() {
                @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {
                    failed.incrementAndGet();
                }
            });
            graph.start("005930");
            GraphAudioFrame frame = graph.accept(sample(0, 100)).orElseThrow();

            port.reportFailure(frame);

            assertEquals(1, failed.get());
        }
        assertNull(port.listener);
    }

    private static TimeSeriesSample sample(long second, double value) {
        return new TimeSeriesSample("005930", value, START.plusSeconds(second));
    }

    private static final class RecordingPort implements SonificationPort {
        private final List<GraphAudioFrame> frames = new CopyOnWriteArrayList<>();
        private boolean closed;
        @Override public void play(GraphAudioFrame frame) { frames.add(frame); }
        @Override public void stop() {}
        @Override public void setVolume(double volume) {}
        @Override public void close() { closed = true; }
    }

    private static final class AsyncFailingPort implements SonificationPort {
        private SonificationOutputListener listener;
        @Override public void play(GraphAudioFrame frame) {}
        @Override public void stop() {}
        @Override public void setVolume(double volume) {}
        @Override public void addOutputListener(SonificationOutputListener listener) { this.listener = listener; }
        @Override public void removeOutputListener(SonificationOutputListener listener) {
            if (this.listener == listener) this.listener = null;
        }
        void reportFailure(GraphAudioFrame frame) {
            listener.onPlaybackFailed(frame, new IllegalStateException("async audio failure"));
        }
    }
}
