package org.ossproject.application.port;

import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;

import java.util.List;
import java.util.Optional;

/**
 * 상태를 가진 주문 처리 포트.
 *
 * <p>기존 {@link OrderPort} 는 {@code submit} 이 즉시 결과를 돌려주는 동기 구조라 실제
 * 증권사의 "접수 후 나중에 체결" 흐름을 표현할 수 없다. 이 포트는 주문을 접수하고
 * 이후 상태 변화는 {@link OrderEventSource} 로 통지한다.
 *
 * <p>{@link OrderPort} 는 화면 계층이 계속 쓰므로 그대로 두었다.
 */
public interface OrderLifecyclePort {

    /**
     * 주문을 접수한다. 반환되는 주문은 접수 시점의 상태이며, 체결은 이후에 통지된다.
     *
     * @throws IllegalStateException 잔고·보유 수량이 부족한 경우
     */
    Order submit(OrderCommand command);

    /**
     * 미체결 주문을 취소한다.
     *
     * @throws IllegalStateException 이미 종료된 주문인 경우
     * @throws java.util.NoSuchElementException 주문을 찾을 수 없는 경우
     */
    Order cancel(String orderId);

    Optional<Order> findOrder(String orderId);

    /** 아직 종료되지 않은 주문. 최근 접수 순. */
    List<Order> openOrders();

    /** 종료된 주문을 포함한 전체 주문. 최근 접수 순. */
    List<Order> orders();
}
