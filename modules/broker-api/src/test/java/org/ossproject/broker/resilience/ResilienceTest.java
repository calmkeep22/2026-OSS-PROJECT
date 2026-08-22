package org.ossproject.broker.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.broker.error.BrokerAuthException;
import org.ossproject.broker.error.BrokerException;
import org.ossproject.broker.error.BrokerRateLimitException;
import org.ossproject.broker.error.BrokerTransientException;
import org.ossproject.broker.TestClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceTest {

    private static final Instant START = Instant.parse("2026-08-08T01:00:00Z");

    private ResilientExecutor executor(RetryPolicy policy, CircuitBreaker breaker) {
        return new ResilientExecutor(policy, breaker, Sleeper.none());
    }

    private CircuitBreaker openAfter(int failures, TestClock clock) {
        return new CircuitBreaker(failures, Duration.ofSeconds(30), 1, clock);
    }

    @Test
    @DisplayName("일시적 오류는 재시도하고 성공하면 결과를 돌려준다")
    void retriesTransientFailure() {
        TestClock clock = new TestClock(START);
        AtomicInteger attempts = new AtomicInteger();
        ResilientExecutor executor = executor(RetryPolicy.immediate(3), openAfter(5, clock));

        String result = executor.call("시세 조회", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new BrokerTransientException("일시적 네트워크 오류");
            }
            return "성공";
        });

        assertEquals("성공", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("모든 시도가 실패하면 마지막 오류를 담아 던진다")
    void failsAfterAllAttempts() {
        TestClock clock = new TestClock(START);
        AtomicInteger attempts = new AtomicInteger();
        ResilientExecutor executor = executor(RetryPolicy.immediate(3), openAfter(10, clock));

        BrokerException thrown = assertThrows(BrokerTransientException.class,
                () -> executor.call("시세 조회", () -> {
                    attempts.incrementAndGet();
                    throw new BrokerTransientException("서버 응답 없음");
                }));

        assertEquals(3, attempts.get());
        assertTrue(thrown.getMessage().contains("3회"));
    }

    @Test
    @DisplayName("인증 오류는 재시도하지 않고 즉시 던진다")
    void doesNotRetryAuthFailure() {
        TestClock clock = new TestClock(START);
        AtomicInteger attempts = new AtomicInteger();
        ResilientExecutor executor = executor(RetryPolicy.immediate(3), openAfter(5, clock));

        assertThrows(BrokerAuthException.class, () -> executor.call("주문 접수", () -> {
            attempts.incrementAndGet();
            throw new BrokerAuthException("API 키가 올바르지 않습니다.");
        }));

        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("인증 오류는 회로를 열지 않는다")
    void authFailureDoesNotTripCircuit() {
        TestClock clock = new TestClock(START);
        CircuitBreaker breaker = openAfter(2, clock);
        ResilientExecutor executor = executor(RetryPolicy.none(), breaker);

        for (int i = 0; i < 5; i++) {
            assertThrows(BrokerAuthException.class,
                    () -> executor.call("주문 접수", () -> {
                        throw new BrokerAuthException("API 키가 올바르지 않습니다.");
                    }));
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("연속 실패가 임계값에 닿으면 회로가 열려 즉시 실패한다")
    void tripsCircuitAfterThreshold() {
        TestClock clock = new TestClock(START);
        CircuitBreaker breaker = openAfter(2, clock);
        ResilientExecutor executor = executor(RetryPolicy.none(), breaker);
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            assertThrows(BrokerTransientException.class, () -> executor.call("시세 조회", () -> {
                calls.incrementAndGet();
                throw new BrokerTransientException("서버 응답 없음");
            }));
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertThrows(CircuitOpenException.class,
                () -> executor.call("시세 조회", calls::incrementAndGet));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("차단 시간이 지나면 시험 호출을 허용하고 성공하면 회로가 닫힌다")
    void recoversThroughHalfOpen() {
        TestClock clock = new TestClock(START);
        CircuitBreaker breaker = openAfter(1, clock);
        ResilientExecutor executor = executor(RetryPolicy.none(), breaker);

        assertThrows(BrokerTransientException.class, () -> executor.call("시세 조회", () -> {
            throw new BrokerTransientException("서버 응답 없음");
        }));
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        clock.advance(Duration.ofSeconds(30));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());

        assertEquals("성공", executor.call("시세 조회", () -> "성공"));
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    @DisplayName("회복 확인 중 실패하면 다시 차단된다")
    void reopensWhenHalfOpenTrialFails() {
        TestClock clock = new TestClock(START);
        CircuitBreaker breaker = openAfter(1, clock);
        ResilientExecutor executor = executor(RetryPolicy.none(), breaker);

        assertThrows(BrokerTransientException.class, () -> executor.call("시세 조회", () -> {
            throw new BrokerTransientException("서버 응답 없음");
        }));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.allowRequest());

        assertThrows(BrokerTransientException.class, () -> executor.call("시세 조회", () -> {
            throw new BrokerTransientException("여전히 응답 없음");
        }));

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.allowRequest());
    }

    @Test
    @DisplayName("호출 한도 초과 시 서버가 알려 준 대기 시간을 따른다")
    void honoursRetryAfter() {
        TestClock clock = new TestClock(START);
        List<Duration> slept = new ArrayList<>();
        ResilientExecutor executor = new ResilientExecutor(
                RetryPolicy.immediate(2), openAfter(5, clock), slept::add);
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.call("시세 조회", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new BrokerRateLimitException("호출 한도 초과", Duration.ofSeconds(3));
            }
            return "성공";
        });

        assertEquals("성공", result);
        assertEquals(List.of(Duration.ofSeconds(3)), slept);
    }

    @Test
    @DisplayName("예상하지 못한 예외는 감싸서 던지고 회로에 실패로 기록한다")
    void wrapsUnexpectedException() {
        TestClock clock = new TestClock(START);
        CircuitBreaker breaker = openAfter(1, clock);
        ResilientExecutor executor = executor(RetryPolicy.immediate(3), breaker);
        AtomicInteger attempts = new AtomicInteger();

        BrokerException thrown = assertThrows(BrokerException.class,
                () -> executor.call("주문 접수", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("파싱 실패");
                }));

        assertEquals(1, attempts.get());
        assertTrue(thrown.getMessage().contains("예상하지 못한 오류"));
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    @DisplayName("지수 백오프는 상한을 넘지 않는다")
    void capsBackoffDelay() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofMillis(500),
                Duration.ofSeconds(5), 2.0, 0.0);

        assertEquals(Duration.ofMillis(500), policy.delayAfterAttempt(1));
        assertEquals(Duration.ofSeconds(1), policy.delayAfterAttempt(2));
        assertEquals(Duration.ofSeconds(2), policy.delayAfterAttempt(3));
        assertEquals(Duration.ofSeconds(5), policy.delayAfterAttempt(9));
    }

    @Test
    @DisplayName("수동 재설정으로 회로를 즉시 닫는다")
    void resetsCircuitManually() {
        TestClock clock = new TestClock(START);
        CircuitBreaker breaker = openAfter(1, clock);
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());

        breaker.reset();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.allowRequest());
    }
}
