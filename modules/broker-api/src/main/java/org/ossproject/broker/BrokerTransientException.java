package org.ossproject.broker;

/**
 * 일시적인 오류. 네트워크 단절, 타임아웃, 5xx 응답.
 *
 * <p>재시도 대상이다.
 */
public class BrokerTransientException extends BrokerException {

    private static final long serialVersionUID = 1L;

    public BrokerTransientException(String message) {
        super(message);
    }

    public BrokerTransientException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
