package org.ossproject.application.port;

import org.ossproject.finance.model.order.Order;
import org.ossproject.finance.model.order.OrderCommand;

import java.util.List;
import java.util.Optional;

/**
 * Boundary for submitting and tracking stateful orders.
 *
 * <p>Broker and mock adapters expose the same lifecycle contract. Later state changes are
 * delivered through {@link OrderEventSource}.
 */
public interface OrderLifecyclePort {
    Order submit(OrderCommand command);

    Order cancel(String orderId);

    Optional<Order> findOrder(String orderId);

    List<Order> openOrders();

    List<Order> orders();
}
