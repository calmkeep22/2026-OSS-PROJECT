package org.ossproject.broker.error;

/**
 * 인증 실패. API 키가 틀렸거나 토큰이 만료된 경우.
 *
 * <p>재시도해도 같은 결과이므로 재시도 대상이 아니다. 토큰 만료는 호출부에서 재발급 후
 * 다시 시도한다.
 */
public class BrokerAuthException extends BrokerException {

    private static final long serialVersionUID = 1L;

    public BrokerAuthException(String message) {
        super(message);
    }

    public BrokerAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
