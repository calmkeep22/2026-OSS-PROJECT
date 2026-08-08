package org.ossproject.kiwoom.http;

/**
 * HTTP 전송 계층.
 *
 * <p>구현체는 네트워크 오류를
 * {@link org.ossproject.broker.BrokerTransientException} 으로 변환해야 한다.
 */
@FunctionalInterface
public interface HttpTransport {

    HttpTextResponse send(HttpTextRequest request);
}
