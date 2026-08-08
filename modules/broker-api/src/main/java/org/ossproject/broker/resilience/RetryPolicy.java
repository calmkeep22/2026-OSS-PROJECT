package org.ossproject.broker.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 지수 백오프 재시도 정책.
 *
 * @param maxAttempts  최초 시도를 포함한 최대 시도 횟수. 1이면 재시도하지 않는다
 * @param initialDelay 첫 재시도까지의 대기 시간
 * @param maxDelay     대기 시간 상한
 * @param multiplier   시도마다 대기 시간에 곱할 배수
 * @param jitterRatio  대기 시간에 더할 무작위 비율(0.0 ~ 1.0). 0이면 지터 없음
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double multiplier,
        double jitterRatio
) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("최대 시도 횟수는 1 이상이어야 합니다.");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("초기 대기 시간은 0 이상이어야 합니다.");
        }
        if (maxDelay == null || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("최대 대기 시간은 초기 대기 시간 이상이어야 합니다.");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("배수는 1.0 이상이어야 합니다.");
        }
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException("지터 비율은 0.0 이상 1.0 이하여야 합니다.");
        }
    }

    /** 네트워크 호출 기본값. 3회, 0.5초에서 시작해 2배씩, 최대 5초, 지터 20%. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(5), 2.0, 0.2);
    }

    /** 재시도하지 않는다. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0, 0.0);
    }

    /** 테스트용. 대기 없이 지정 횟수만 재시도한다. */
    public static RetryPolicy immediate(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ZERO, Duration.ZERO, 1.0, 0.0);
    }

    /**
     * {@code attempt} 번째 시도 실패 후의 대기 시간.
     *
     * @param attempt 1부터 시작하는 시도 번호
     */
    public Duration delayAfterAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("시도 번호는 1 이상이어야 합니다.");
        }
        double millis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1.0);
        long capped = (long) Math.min(millis, maxDelay.toMillis());
        if (jitterRatio > 0.0 && capped > 0) {
            long jitter = (long) (capped * jitterRatio);
            if (jitter > 0) {
                capped += ThreadLocalRandom.current().nextLong(jitter + 1);
            }
        }
        return Duration.ofMillis(Math.min(capped, maxDelay.toMillis() + (long) (maxDelay.toMillis() * jitterRatio)));
    }
}
