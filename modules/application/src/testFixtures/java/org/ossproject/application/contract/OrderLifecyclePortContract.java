package org.ossproject.application.contract;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.finance.model.order.OrderCommand;
import org.ossproject.finance.model.order.OrderStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reusable contract for mock and broker order-lifecycle adapters. */
public abstract class OrderLifecyclePortContract {
    protected abstract OrderLifecyclePort createOrderPort();

    protected abstract OrderCommand validOrder();

    @Test
    void submittedOrderCanBeFoundListedAndCancelled() {
        OrderLifecyclePort port = createOrderPort();

        var submitted = port.submit(validOrder());

        assertFalse(submitted.orderId().isBlank());
        assertEquals(submitted, port.findOrder(submitted.orderId()).orElseThrow());
        assertTrue(port.openOrders().contains(submitted));
        assertTrue(port.orders().contains(submitted));

        var cancelled = port.cancel(submitted.orderId());
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertTrue(port.openOrders().isEmpty());
    }
}
