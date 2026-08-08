package org.ossproject.sonification.infrastructure.sound;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.model.GraphAudioFrame;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PcmGraphSonificationAdapterTest {
    @Test void interpolatesPitchAsASmoothLogarithmicGlide() {
        GraphAudioFrame frame = frame();
        assertEquals(220, PcmGraphSonificationAdapter.interpolateFrequency(frame, 0), 0.001);
        assertEquals(440, PcmGraphSonificationAdapter.interpolateFrequency(frame, 0.5), 0.001);
        assertEquals(880, PcmGraphSonificationAdapter.interpolateFrequency(frame, 1), 0.001);
        assertThrows(IllegalArgumentException.class,
                () -> PcmGraphSonificationAdapter.interpolateFrequency(frame, 1.1));
    }

    @Test void validatesVolumeAndCloseIsIdempotent() {
        PcmGraphSonificationAdapter adapter = new PcmGraphSonificationAdapter();
        adapter.setVolume(0.5);
        assertThrows(IllegalArgumentException.class, () -> adapter.setVolume(-0.1));
        assertThrows(IllegalArgumentException.class, () -> adapter.setVolume(1.1));
        adapter.close();
        assertDoesNotThrow(adapter::close);
        assertThrows(IllegalStateException.class, () -> adapter.setVolume(0.5));
        assertThrows(IllegalStateException.class, () -> adapter.play(frame()));
    }

    @Test void reportsAudioDeviceFailuresRaisedOnThePlaybackThread() throws InterruptedException {
        PcmGraphSonificationAdapter adapter = new PcmGraphSonificationAdapter(format -> {
            throw new IllegalStateException("test audio device unavailable");
        });
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<RuntimeException> reported = new AtomicReference<>();
        adapter.addOutputListener((frame, error) -> {
            reported.set(error);
            failed.countDown();
        });

        adapter.play(frame());

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertEquals("test audio device unavailable", reported.get().getMessage());
        adapter.close();
    }

    private static GraphAudioFrame frame() {
        return new GraphAudioFrame("005930", 220, 880, 5, 1,
                73_500, Duration.ofSeconds(1), Instant.parse("2026-08-08T00:00:00Z"));
    }
}
