package org.ossproject.application.port;

/** {@link OrderEventListener} 를 등록·해제할 수 있는 발행자. */
public interface OrderEventSource {

    void addOrderEventListener(OrderEventListener listener);

    void removeOrderEventListener(OrderEventListener listener);
}
