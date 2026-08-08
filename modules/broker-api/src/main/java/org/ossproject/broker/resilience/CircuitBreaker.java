package org.ossproject.broker.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 회로 차단기.
 *
 * <p>증권사 서버가 죽었을 때 매번 타임아웃을 기다리면 화면이 통째로 멈춘다. 실패가
 * 연달아 쌓이면 회로를 열어 즉시 실패시키고, 일정 시간이 지나면 한 번만 시험 호출을
 * 보내 회복을 확인한다.
 *
 * <p>상태 전이: {@code CLOSED → (연속 실패) → OPEN → (대기 시간 경과) → HALF_OPEN}.
 * HALF_OPEN 에서 성공하면 CLOSED 로, 실패하면 다시 OPEN 으로 돌아간다.
 */
public final class CircuitBreaker {

    public enum State {
        /** 정상. 모든 호출을 통과시킨다. */
        CLOSED("정상"),
        /** 차단됨. 호출을 즉시 실패시킨다. */
        OPEN("차단됨"),
        /** 회복 확인 중. 시험 호출만 통과시킨다. */
        HALF_OPEN("회복 확인 중");

        private final String displayName;

        State(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private final int halfOpenSuccessThreshold;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private int halfOpenSuccesses;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, Duration openDuration,
                          int halfOpenSuccessThreshold, Clock clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("실패 임계값은 1 이상이어야 합니다.");
        }
        if (openDuration == null || openDuration.isNegative()) {
            throw new IllegalArgumentException("차단 시간은 0 이상이어야 합니다.");
        }
        if (halfOpenSuccessThreshold < 1) {
            throw new IllegalArgumentException("회복 확인 성공 횟수는 1 이상이어야 합니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.halfOpenSuccessThreshold = halfOpenSuccessThreshold;
        this.clock = clock;
    }

    /** 연속 5회 실패 시 30초 차단, 1회 성공하면 회복. */
    public static CircuitBreaker defaults(Clock clock) {
        return new CircuitBreaker(5, Duration.ofSeconds(30), 1, clock);
    }

    /** 호출을 보내도 되는지 판단한다. 필요하면 OPEN 에서 HALF_OPEN 으로 넘긴다. */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && hasOpenDurationElapsed()) {
            state = State.HALF_OPEN;
            halfOpenSuccesses = 0;
        }
        return state != State.OPEN;
    }

    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= halfOpenSuccessThreshold) {
                reset();
            }
            return;
        }
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            trip();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            trip();
        }
    }

    public synchronized State state() {
        if (state == State.OPEN && hasOpenDurationElapsed()) {
            return State.HALF_OPEN;
        }
        return state;
    }

    /** 사용자가 수동으로 재연결을 요청했을 때 회로를 되돌린다. */
    public synchronized void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        halfOpenSuccesses = 0;
        openedAt = null;
    }

    private void trip() {
        state = State.OPEN;
        openedAt = clock.instant();
        consecutiveFailures = failureThreshold;
        halfOpenSuccesses = 0;
    }

    private boolean hasOpenDurationElapsed() {
        return openedAt != null && !clock.instant().isBefore(openedAt.plus(openDuration));
    }
}
