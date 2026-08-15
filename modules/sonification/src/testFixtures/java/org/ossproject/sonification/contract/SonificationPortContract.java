package org.ossproject.sonification.contract;

import org.junit.jupiter.api.Test;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.port.SonificationOutputListener;
import org.ossproject.sonification.port.SonificationPort;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reusable lifecycle and input contract for graph-audio output adapters. */
public abstract class SonificationPortContract {

    protected abstract SonificationPort createPort();

    @Test
    void acceptsFramesAndReportsAnExplicitOverflowPolicy() {
        SonificationPort port = createPort();
        try {
            assertNotNull(port.overflowPolicy());
            assertDoesNotThrow(() -> port.play(frame()));
            assertDoesNotThrow(port::stop);
        } finally {
            port.close();
        }
    }

    @Test
    void enforcesVolumeAndNullFrameRules() {
        SonificationPort port = createPort();
        try {
            assertDoesNotThrow(() -> port.setVolume(0));
            assertDoesNotThrow(() -> port.setVolume(1));
            assertThrows(IllegalArgumentException.class, () -> port.setVolume(-0.01));
            assertThrows(IllegalArgumentException.class, () -> port.setVolume(1.01));
            assertThrows(IllegalArgumentException.class, () -> port.setVolume(Double.NaN));
            assertThrows(NullPointerException.class, () -> port.play(null));
        } finally {
            port.close();
        }
    }

    @Test
    void closesIdempotentlyAndRejectsNewWork() {
        SonificationPort port = createPort();
        SonificationOutputListener listener = (frame, failure) -> { };
        port.addOutputListener(listener);

        assertDoesNotThrow(port::close);
        assertDoesNotThrow(port::close);
        assertDoesNotThrow(port::stop);
        assertDoesNotThrow(() -> port.removeOutputListener(listener));
        assertThrows(IllegalStateException.class, () -> port.play(frame()));
        assertThrows(IllegalStateException.class, () -> port.setVolume(0.5));
        assertThrows(IllegalStateException.class, () -> port.addOutputListener(listener));
    }

    protected static GraphAudioFrame frame() {
        return new GraphAudioFrame("contract-stream", 220, 440, 1, 0.25,
                101, Duration.ofMillis(10), Instant.parse("2026-08-08T00:00:00Z"));
    }
}
