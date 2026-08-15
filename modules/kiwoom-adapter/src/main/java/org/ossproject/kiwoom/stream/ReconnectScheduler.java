package org.ossproject.kiwoom.stream;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 재연결 예약.
 *
 * <p>테스트가 실제로 기다리지 않아도 되도록 분리했다.
 */
interface ReconnectScheduler {

    void schedule(Duration delay, Runnable task);

    void shutdown();

    /** 데몬 스레드 하나로 예약을 처리하는 기본 구현. */
    static ReconnectScheduler daemon() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kiwoom-reconnect");
            thread.setDaemon(true);
            return thread;
        });
        return new ReconnectScheduler() {
            @Override
            public void schedule(Duration delay, Runnable task) {
                executor.schedule(task, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
    }
}
