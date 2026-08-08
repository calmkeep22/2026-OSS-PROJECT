package org.ossproject.broker.resilience;

import org.ossproject.broker.BrokerException;
import org.ossproject.broker.BrokerRateLimitException;
import org.ossproject.broker.BrokerTransientException;
import org.ossproject.broker.SensitiveDataMasker;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 재시도와 회로 차단을 함께 적용해 호출을 감싼다.
 *
 * <p>실패 처리 원칙:
 * <ul>
 *   <li>{@link BrokerException#isRetryable()} 이 참이면 정책에 따라 재시도하고, 회로에 실패로 기록한다.</li>
 *   <li>인증 실패처럼 재시도해도 소용없는 오류는 즉시 던지고 회로에 기록하지 않는다.
 *       서버는 정상 응답했으므로 서버 장애가 아니기 때문이다.</li>
 *   <li>예상하지 못한 예외는 회로에 실패로 기록하고 즉시 던진다. 무엇이 잘못됐는지
 *       모르는 상태에서 같은 호출을 반복하는 것은 위험하다.</li>
 * </ul>
 *
 * <p>{@link BrokerRateLimitException} 은 서버가 알려 준 대기 시간을 우선 따른다.
 */
public final class ResilientExecutor {

    /** 호출 결과가 없는 작업. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    private final RetryPolicy policy;
    private final CircuitBreaker circuitBreaker;
    private final Sleeper sleeper;

    public ResilientExecutor(RetryPolicy policy, CircuitBreaker circuitBreaker, Sleeper sleeper) {
        if (policy == null) {
            throw new IllegalArgumentException("재시도 정책은 필수입니다.");
        }
        if (circuitBreaker == null) {
            throw new IllegalArgumentException("회로 차단기는 필수입니다.");
        }
        if (sleeper == null) {
            throw new IllegalArgumentException("대기 구현은 필수입니다.");
        }
        this.policy = policy;
        this.circuitBreaker = circuitBreaker;
        this.sleeper = sleeper;
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    /**
     * 작업을 실행한다.
     *
     * @param operation 로그와 오류 메시지에 쓸 작업 이름. 민감한 값을 넣지 않는다
     * @throws CircuitOpenException 회로가 열려 있는 경우
     * @throws BrokerException      모든 시도가 실패한 경우
     */
    public <T> T call(String operation, Callable<T> task) {
        BrokerException lastFailure = null;

        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (!circuitBreaker.allowRequest()) {
                throw new CircuitOpenException(
                        operation + " 요청을 보내지 않았습니다. 증권사 연결이 일시 차단된 상태입니다.");
            }

            try {
                T result = task.call();
                circuitBreaker.recordSuccess();
                return result;
            } catch (BrokerException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                circuitBreaker.recordFailure();
                lastFailure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BrokerTransientException(operation + " 중 작업이 중단되었습니다.", e);
            } catch (Exception e) {
                circuitBreaker.recordFailure();
                throw new BrokerException(
                        operation + " 중 예상하지 못한 오류가 발생했습니다. "
                                + SensitiveDataMasker.mask(String.valueOf(e.getMessage())), e);
            }

            if (attempt < policy.maxAttempts()) {
                waitBeforeRetry(operation, lastFailure, attempt);
            }
        }

        throw new BrokerTransientException(
                operation + " 을(를) " + policy.maxAttempts() + "회 시도했지만 실패했습니다. "
                        + (lastFailure == null ? "" : SensitiveDataMasker.mask(lastFailure.getMessage())),
                lastFailure);
    }

    /** 결과가 없는 작업을 실행한다. */
    public void run(String operation, ThrowingRunnable task) {
        call(operation, () -> {
            task.run();
            return null;
        });
    }

    private void waitBeforeRetry(String operation, BrokerException failure, int attempt) {
        Duration delay = policy.delayAfterAttempt(attempt);
        if (failure instanceof BrokerRateLimitException rateLimit) {
            Duration serverHint = rateLimit.retryAfter().orElse(null);
            // 서버가 알려 준 대기 시간이 더 길면 그쪽을 따른다.
            if (serverHint != null && serverHint.compareTo(delay) > 0) {
                delay = serverHint;
            }
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerTransientException(operation + " 재시도 대기 중 작업이 중단되었습니다.", e);
        }
    }
}
