package org.ossproject.broker.error;

import org.ossproject.broker.auth.SensitiveDataMasker;

/**
 * 증권사 연동 오류의 최상위 타입.
 *
 * <p>메시지는 화면과 음성 안내에 그대로 쓰이므로 한국어로 작성한다. API 키나 토큰이
 * 메시지에 섞여 들어가지 않도록 {@link org.ossproject.broker.auth.SensitiveDataMasker} 를 거친 문자열만 넣는다.
 */
public class BrokerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BrokerException(String message) {
        super(message);
    }

    public BrokerException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 다시 시도하면 성공할 수 있는 오류인지 여부. */
    public boolean isRetryable() {
        return false;
    }
}
