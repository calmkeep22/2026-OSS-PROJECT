package org.ossproject.broker.resilience;

import java.time.Duration;

/**
 * 재시도 사이의 대기. 테스트에서 실제로 잠들지 않도록 분리했다.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * 지정한 시간만큼 대기한다.
     *
     * @throws InterruptedException 대기 중 인터럽트된 경우
     */
    void sleep(Duration duration) throws InterruptedException;

    /** 실제로 스레드를 재우는 기본 구현. */
    static Sleeper system() {
        return duration -> {
            if (duration != null && !duration.isZero() && !duration.isNegative()) {
                Thread.sleep(duration.toMillis());
            }
        };
    }

    /** 대기하지 않는다. 테스트용. */
    static Sleeper none() {
        return duration -> {
            // 아무것도 하지 않는다.
        };
    }
}
