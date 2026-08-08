package org.ossproject.accessibility;

import org.junit.jupiter.api.Test;
import org.ossproject.accessibility.notification.SpeechListener;
import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechPriority;
import org.ossproject.accessibility.notification.SpeechQueue;
import org.ossproject.accessibility.notification.SpeechQueueConfig;
import org.ossproject.accessibility.notification.SpeechMergePolicy;
import org.ossproject.accessibility.notification.SpeechRequest;
import org.ossproject.accessibility.port.SpeechPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SpeechQueueTest {
    @Test
    void mergesDuplicateRequestWhileFirstIsBeingSpoken() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port)) {
            assertTrue(queue.announce(new SpeechRequest("현재가 7만원", SpeechPriority.INFORMATION, "quote-005930")));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            assertFalse(queue.announce(new SpeechRequest("현재가 7만1천원", SpeechPriority.INFORMATION, "quote-005930")));
            port.release.countDown();
        }
    }

    @Test
    void higherPriorityRequestInterruptsCurrentSpeech() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        CountDownLatch interrupted = new CountDownLatch(1);
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.addListener(new SpeechListener() {
                @Override public void onInterrupted(SpeechRequest request) { interrupted.countDown(); }
            });
            queue.announce(new SpeechRequest("일반 정보", SpeechPriority.INFORMATION, "info"));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            queue.announce(new SpeechRequest("주문 실패", SpeechPriority.CRITICAL, "critical"));
            assertTrue(port.stopped.await(2, TimeUnit.SECONDS));
            assertTrue(port.secondSpoken.await(2, TimeUnit.SECONDS));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("일반 정보", "주문 실패"), port.spoken);
        }
    }

    @Test
    void replacePendingKeepsTheLatestRealTimeValue() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.announce(new SpeechRequest("화면 안내", SpeechPriority.USER_REQUEST, "screen"));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            queue.announce(new SpeechRequest("현재가 7만원", SpeechPriority.INFORMATION, "quote",
                    SpeechMergePolicy.REPLACE_PENDING));
            queue.announce(new SpeechRequest("현재가 7만1천원", SpeechPriority.INFORMATION, "quote",
                    SpeechMergePolicy.REPLACE_PENDING));
            port.release.countDown();
            assertTrue(port.secondSpoken.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("화면 안내", "현재가 7만1천원"), port.spoken);
        }
    }

    @Test
    void rejectsOperationsAfterClose() {
        SpeechQueue queue = new SpeechQueue(new FailingThenSucceedingPort());
        queue.close();
        assertTrue(queue.isClosed());
        assertThrows(IllegalStateException.class, () -> queue.announce(
                new SpeechRequest("안내", SpeechPriority.INFORMATION, "notice")));
        assertThrows(IllegalStateException.class, () -> queue.setOptions(SpeechOptions.DEFAULT));
        assertThrows(IllegalStateException.class, () -> queue.addListener(new SpeechListener() {}));
        queue.close();
    }

    @Test
    void boundedQueueEvictsLowerPriorityPendingSpeech() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port, SpeechOptions.DEFAULT, new SpeechQueueConfig(1))) {
            queue.announce(new SpeechRequest("현재 화면", SpeechPriority.USER_REQUEST, "screen"));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            assertTrue(queue.announce(new SpeechRequest("일반 시세", SpeechPriority.INFORMATION, "quote")));
            assertTrue(queue.announce(new SpeechRequest("이상 감지", SpeechPriority.ALERT, "alert")));
            assertTrue(port.secondSpoken.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("현재 화면", "이상 감지"), port.spoken);
        }
    }

    @Test
    void clearAllowsANewRequestWithTheSameKeyWithoutLosingItsTracking() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.announce(new SpeechRequest("이전 안내", SpeechPriority.INFORMATION, "same-key"));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            queue.clear();
            assertTrue(queue.announce(new SpeechRequest("새 안내", SpeechPriority.INFORMATION, "same-key")));
            assertTrue(port.secondSpoken.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("이전 안내", "새 안내"), port.spoken);
        }
    }

    @Test
    void notifiesListenersOnStartedAndCompleted() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.addListener(new SpeechListener() {
                @Override public void onStarted(SpeechRequest request) { events.add("started:" + request.text()); }
                @Override public void onCompleted(SpeechRequest request) {
                    events.add("completed:" + request.text());
                    completed.countDown();
                }
            });
            queue.announce(new SpeechRequest("안내", SpeechPriority.INFORMATION, "notice"));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            port.release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("started:안내", "completed:안내"), events);
        }
    }

    @Test
    void notifiesOnFailedAndKeepsProcessingNextRequest() throws Exception {
        FailingThenSucceedingPort port = new FailingThenSucceedingPort();
        CountDownLatch failed = new CountDownLatch(1);
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.addListener(new SpeechListener() {
                @Override public void onFailed(SpeechRequest request, RuntimeException error) { failed.countDown(); }
            });
            queue.announce(new SpeechRequest("고장", SpeechPriority.INFORMATION, "broken"));
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            queue.announce(new SpeechRequest("정상", SpeechPriority.INFORMATION, "ok"));
            assertTrue(port.secondSpoken.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void listenerExceptionsDoNotBreakTheQueue() throws Exception {
        BlockingSpeechPort port = new BlockingSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port)) {
            queue.addListener(new SpeechListener() {
                @Override public void onStarted(SpeechRequest request) { throw new RuntimeException("listener bug"); }
            });
            queue.announce(new SpeechRequest("안내", SpeechPriority.INFORMATION, "notice"));
            assertTrue(port.started.await(2, TimeUnit.SECONDS));
            port.release.countDown();
        }
    }

    @Test
    void setOptionsForwardsToThePort() {
        BlockingSpeechPort port = new BlockingSpeechPort();
        try (SpeechQueue queue = new SpeechQueue(port)) {
            SpeechOptions options = SpeechOptions.DEFAULT.withRate(1.5).withVolume(40);
            queue.setOptions(options);
            assertEquals(options, queue.options());
            assertEquals(options, port.appliedOptions);
        }
    }

    private static final class BlockingSpeechPort implements SpeechPort {
        private final List<String> spoken = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final CountDownLatch secondSpoken = new CountDownLatch(1);
        private volatile SpeechOptions appliedOptions;

        @Override public void speak(String text) throws InterruptedException {
            spoken.add(text);
            if (spoken.size() == 1) { started.countDown(); release.await(); }
            else secondSpoken.countDown();
        }
        @Override public void stop() { stopped.countDown(); release.countDown(); }
        @Override public void applyOptions(SpeechOptions options) { appliedOptions = options; }
    }

    private static final class FailingThenSucceedingPort implements SpeechPort {
        private final CountDownLatch secondSpoken = new CountDownLatch(1);
        private boolean first = true;

        @Override public void speak(String text) {
            if (first) { first = false; throw new RuntimeException("boom"); }
            secondSpoken.countDown();
        }
        @Override public void stop() {}
    }
}
