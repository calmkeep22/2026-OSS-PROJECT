package org.ossproject.accessibility;

import org.junit.jupiter.api.Test;
import org.ossproject.accessibility.notification.SpeechPriority;
import org.ossproject.accessibility.notification.SpeechQueue;
import org.ossproject.accessibility.notification.SpeechRequest;
import org.ossproject.accessibility.port.SpeechPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Worked example, referenced from the module README: how to plug a custom
// SpeechPort into SpeechQueue instead of an OS adapter from SpeechAdapterFactory.
class CustomSpeechPortExampleTest {
    @Test void pluggingInACustomSpeechPort() throws Exception {
        ConsoleSpeechPort port = new ConsoleSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.announce(new SpeechRequest("커스텀 백엔드 테스트", SpeechPriority.INFORMATION, "demo"));
            assertTrue(port.spokenLatch.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("커스텀 백엔드 테스트"), port.spoken);
        }
    }

    private static final class ConsoleSpeechPort implements SpeechPort {
        private final List<String> spoken = new CopyOnWriteArrayList<>();
        private final CountDownLatch spokenLatch = new CountDownLatch(1);

        @Override public void speak(String text) {
            System.out.println("[TTS] " + text);
            spoken.add(text);
            spokenLatch.countDown();
        }
        @Override public void stop() {}
    }
}
