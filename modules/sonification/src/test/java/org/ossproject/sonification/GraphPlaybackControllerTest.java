package org.ossproject.sonification;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.model.GraphValueScale;
import org.ossproject.sonification.model.TimeSeriesSample;
import org.ossproject.sonification.port.SonificationOverflowPolicy;
import org.ossproject.sonification.port.SonificationPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class GraphPlaybackControllerTest {
    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test void playsTheLoadedGraphInOrderAndCompletes() throws InterruptedException {
        RecordingPort port = new RecordingPort();
        List<TimeSeriesSample> samples = samples();
        CountDownLatch completed = new CountDownLatch(1);
        try (StreamingGraphSonifier sonifier = new StreamingGraphSonifier(port);
             GraphPlaybackController playback = new GraphPlaybackController(sonifier)) {
            playback.addListener(new GraphPlaybackListener() {
                @Override public void onStateChanged(GraphPlaybackState state) {
                    if (state == GraphPlaybackState.COMPLETED) completed.countDown();
                }
            });
            playback.load(samples, GraphValueScale.automatic(samples), Duration.ofMillis(10));
            playback.play();

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(GraphPlaybackState.COMPLETED, playback.state());
            assertEquals(List.of(100d, 110d, 90d),
                    port.frames.stream().map(GraphAudioFrame::currentValue).toList());
        }
    }

    @Test void supportsPointPreviewReplaySpeedAndLifecycle() {
        RecordingPort port = new RecordingPort();
        List<TimeSeriesSample> samples = samples();
        StreamingGraphSonifier sonifier = new StreamingGraphSonifier(port);
        GraphPlaybackController playback = new GraphPlaybackController(sonifier);
        playback.load(samples, GraphValueScale.automatic(samples), Duration.ofMillis(50));

        GraphAudioFrame selected = playback.seek(1);
        assertEquals(1, playback.currentIndex());
        assertEquals(110, selected.currentValue(), 0.001);
        assertEquals(Duration.ofMillis(300), selected.duration());
        playback.setSpeed(4.0);
        assertEquals(4.0, playback.speed());
        assertThrows(IllegalArgumentException.class, () -> playback.setSpeed(5.0));
        playback.stop();
        assertEquals(-1, playback.currentIndex());
        playback.close();
        sonifier.close();
        assertThrows(IllegalStateException.class, playback::play);
        assertDoesNotThrow(playback::close);
    }

    @Test void playsReducedPlanWhileKeepingOriginalPointsSeekable() throws InterruptedException {
        RecordingPort port = new RecordingPort();
        List<TimeSeriesSample> source = List.of(
                new TimeSeriesSample("005930", 100, START),
                new TimeSeriesSample("005930", 101, START.plusSeconds(1)),
                new TimeSeriesSample("005930", 130, START.plusSeconds(2)),
                new TimeSeriesSample("005930", 102, START.plusSeconds(5)),
                new TimeSeriesSample("005930", 103, START.plusSeconds(10)));
        List<TimeSeriesSample> audible = List.of(source.get(0), source.get(2), source.get(4));
        GraphPlaybackPlan plan = new GraphPlaybackPlan(source, audible,
                List.of(Duration.ofMillis(5), Duration.ofMillis(5), Duration.ofMillis(5)));
        CountDownLatch completed = new CountDownLatch(1);
        try (StreamingGraphSonifier sonifier = new StreamingGraphSonifier(port);
             GraphPlaybackController playback = new GraphPlaybackController(sonifier)) {
            playback.addListener(new GraphPlaybackListener() {
                @Override public void onStateChanged(GraphPlaybackState state) {
                    if (state == GraphPlaybackState.COMPLETED) completed.countDown();
                }
            });
            playback.load(plan, GraphValueScale.automatic(source));
            assertEquals(5, playback.size());
            assertEquals(101, playback.seek(1).currentValue(), 0.001);
            port.frames.clear();
            playback.replay();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(100d, 130d, 103d),
                    port.frames.stream().map(GraphAudioFrame::currentValue).toList());
        }
    }

    @Test void closesAnInjectedSchedulerItOwns() {
        RecordingPort port = new RecordingPort();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        StreamingGraphSonifier sonifier = new StreamingGraphSonifier(port);
        GraphPlaybackController playback = new GraphPlaybackController(sonifier, scheduler);

        playback.close();

        assertTrue(scheduler.isShutdown());
        sonifier.close();
    }

    private static List<TimeSeriesSample> samples() {
        return List.of(new TimeSeriesSample("005930", 100, START),
                new TimeSeriesSample("005930", 110, START.plusSeconds(1)),
                new TimeSeriesSample("005930", 90, START.plusSeconds(2)));
    }

    private static final class RecordingPort implements SonificationPort {
        private final List<GraphAudioFrame> frames = new CopyOnWriteArrayList<>();
        @Override public void play(GraphAudioFrame frame) { frames.add(frame); }
        @Override public void stop() {}
        @Override public void setVolume(double volume) {}
        @Override public SonificationOverflowPolicy overflowPolicy() { return SonificationOverflowPolicy.DROP_OLDEST; }
        @Override public void close() {}
    }
}
