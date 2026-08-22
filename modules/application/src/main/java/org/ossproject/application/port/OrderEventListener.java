package org.ossproject.application.port;

import org.ossproject.finance.model.order.Execution;
import org.ossproject.finance.model.order.Order;

/**
 * 주문 상태 변화 수신자.
 *
 * <p>화면 계층은 이 리스너를 등록해 체결 알림을 음성과 상태음으로 전달한다.
 * 구현체는 반드시 예외를 던지지 않아야 하며, 발행자는 한 리스너의 실패가 다른
 * 리스너에게 영향을 주지 않도록 격리한다.
 */
public interface OrderEventListener {

    /** 주문 상태가 바뀔 때마다 호출된다. 접수, 부분 체결, 전량 체결, 취소, 거부 모두 포함. */
    void onOrderUpdated(Order order);

    /** 체결 한 건이 발생할 때 호출된다. */
    default void onExecution(Execution execution) {
        // 체결 단위 처리가 필요 없는 구현은 onOrderUpdated 만 쓰면 된다.
    }
}
