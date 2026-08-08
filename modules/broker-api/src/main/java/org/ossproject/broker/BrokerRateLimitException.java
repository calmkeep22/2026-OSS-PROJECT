package org.ossproject.broker;

import java.time.Duration;
import java.util.Optional;

/**
 * 증권사 호출 한도 초과.
 *
 * <p>재시도 대상이지만 서버가 알려 준 대기 시간이 있으면 그만큼 기다린 뒤 시도해야 한다.
 */
public class BrokerRateLimitException extends BrokerException {

    private static final long serialVersionUID = 1L;

    private final transient Duration retryAfter;

    public BrokerRateLimitException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** 서버가 지정한 대기 시간. 없으면 비어 있다. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
