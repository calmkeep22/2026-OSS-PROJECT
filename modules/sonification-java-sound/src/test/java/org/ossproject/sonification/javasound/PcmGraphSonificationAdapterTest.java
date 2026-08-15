package org.ossproject.sonification.javasound;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.contract.SonificationPortContract;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.port.SonificationOutputListener;
import org.ossproject.sonification.port.SonificationPort;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmGraphSonificationAdapterTest extends SonificationPortContract {

    @Override
    protected SonificationPort createPort() {
        return new PcmGraphSonificationAdapter(format -> {
            throw new IllegalStateException("contract audio device unavailable");
        });
    }

    @Test
    void interpolatesPitchAsASmoothLogarithmicGlide() {
        GraphAudioFrame frame = frame(73_500, 0);
        assertEquals(220, PcmGraphSonificationAdapter.interpolateFrequency(frame, 0), 0.001);
        assertEquals(440, PcmGraphSonificationAdapter.interpolateFrequency(frame, 0.5), 0.001);
        assertEquals(880, PcmGraphSonificationAdapter.interpolateFrequency(frame, 1), 0.001);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PcmGraphSonificationAdapter.interpolateFrequency(frame, 1.1));
    }

    @Test
    void reportsAudioDeviceFailuresRaisedOnThePlaybackThread() throws InterruptedException {
        PcmGraphSonificationAdapter adapter = new PcmGraphSonificationAdapter(format -> {
            throw new IllegalStateException("test audio device unavailable");
        });
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<RuntimeException> reported = new AtomicReference<>();
        adapter.addOutputListener((frame, error) -> {
            reported.set(error);
            failed.countDown();
        });

        adapter.play(frame(73_500, 0));

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertEquals("test audio device unavailable", reported.get().getMessage());
        adapter.close();
    }

    @Test
    void dropsAndReportsTheOldestPendingFrameWhenTheQueueIsFull() throws InterruptedException {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        SourceDataLine line = blockingLine(writeStarted, releaseWrite);
        PcmGraphSonificationAdapter adapter = new PcmGraphSonificationAdapter(format -> line);
        CountDownLatch dropped = new CountDownLatch(1);
        AtomicReference<GraphAudioFrame> reported = new AtomicReference<>();
        adapter.addOutputListener(new SonificationOutputListener() {
            @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) { }

            @Override public void onFrameDropped(GraphAudioFrame frame) {
                reported.set(frame);
                dropped.countDown();
            }
        });

        GraphAudioFrame first = frame(100, 0);
        GraphAudioFrame oldestPending = frame(101, 1);
        try {
            adapter.play(first);
            assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
            adapter.play(oldestPending);
            adapter.play(frame(102, 2));
            adapter.play(frame(103, 3));

            assertTrue(dropped.await(1, TimeUnit.SECONDS));
            assertSame(oldestPending, reported.get());
        } finally {
            releaseWrite.countDown();
            adapter.close();
        }
    }

    private static GraphAudioFrame frame(double value, long second) {
        return new GraphAudioFrame("005930", 220, 880, 5, 1,
                value, Duration.ofMillis(10),
                Instant.parse("2026-08-08T00:00:00Z").plusSeconds(second));
    }

    private static SourceDataLine blockingLine(CountDownLatch writeStarted,
                                               CountDownLatch releaseWrite) {
        AtomicBoolean open = new AtomicBoolean();
        return (SourceDataLine) Proxy.newProxyInstance(
                PcmGraphSonificationAdapterTest.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "open" -> {
                        open.set(true);
                        yield null;
                    }
                    case "isOpen" -> open.get();
                    case "start", "drain", "flush" -> null;
                    case "write" -> {
                        writeStarted.countDown();
                        releaseWrite.await(1, TimeUnit.SECONDS);
                        yield arguments[2];
                    }
                    case "close" -> {
                        open.set(false);
                        releaseWrite.countDown();
                        yield null;
                    }
                    case "isRunning", "isActive", "isControlSupported" -> false;
                    case "available", "getBufferSize", "getFramePosition" -> 0;
                    case "getLongFramePosition", "getMicrosecondPosition" -> 0L;
                    case "getLevel" -> 0f;
                    case "getControls" -> new javax.sound.sampled.Control[0];
                    case "toString" -> "BlockingSourceDataLine";
                    default -> null;
                });
    }
}
