package org.ossproject.broker.resilience;

import org.ossproject.broker.BrokerException;

/**
 * 회로가 열려 있어 호출을 보내지 않고 즉시 실패시켰을 때 던진다.
 *
 * <p>재시도 대상이 아니다. 회로가 스스로 회복될 때까지 기다려야 한다.
 */
public class CircuitOpenException extends BrokerException {

    private static final long serialVersionUID = 1L;

    public CircuitOpenException(String message) {
        super(message);
    }
}
