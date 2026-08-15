package org.ossproject.finance.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:00:00Z");

    private Order newOrder(long quantity) {
        return Order.create("ORD-1",
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, quantity, new BigDecimal("70000")),
                NOW);
    }

    private Execution execution(String id, long quantity, String price, Instant at) {
        return new Execution(id, "ORD-1", "005930", OrderSide.BUY, quantity, new BigDecimal(price), at);
    }

    @Test
    @DisplayName("새 주문은 접수 대기 상태이고 체결 수량이 0이다")
    void createsNewOrder() {
        Order order = newOrder(10);

        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(0L, order.filledQuantity());
        assertEquals(10L, order.remainingQuantity());
        assertFalse(order.isTerminal());
    }

    @Test
    @DisplayName("부분 체결 후 전량 체결되면 상태와 평균 단가가 갱신된다")
    void appliesPartialThenFullExecution() {
        Order order = newOrder(10).accept(NOW);

        Order partial = order.applyExecution(execution("E-1", 4, "70000", NOW.plusSeconds(1)));
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(4L, partial.filledQuantity());
        assertEquals(6L, partial.remainingQuantity());

        Order filled = partial.applyExecution(execution("E-2", 6, "71000", NOW.plusSeconds(2)));
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(0L, filled.remainingQuantity());
        assertTrue(filled.isTerminal());
        assertEquals(2, filled.executions().size());

        // (4*70000 + 6*71000) / 10 = 70600
        assertEquals(0, new BigDecimal("70600.00").compareTo(filled.averageFilledPrice()));
    }

    @Test
    @DisplayName("남은 수량을 초과하는 체결은 거부한다")
    void rejectsOverfill() {
        Order order = newOrder(10).accept(NOW);

        assertThrows(IllegalArgumentException.class,
                () -> order.applyExecution(execution("E-1", 11, "70000", NOW)));
    }

    @Test
    @DisplayName("전량 체결된 주문에는 더 이상 체결을 반영할 수 없다")
    void rejectsExecutionOnTerminalOrder() {
        Order filled = newOrder(1).accept(NOW)
                .applyExecution(execution("E-1", 1, "70000", NOW));

        assertThrows(IllegalStateException.class,
                () -> filled.applyExecution(execution("E-2", 1, "70000", NOW)));
    }

    @Test
    @DisplayName("다른 주문의 체결은 거부한다")
    void rejectsForeignExecution() {
        Order order = newOrder(10).accept(NOW);
        Execution foreign = new Execution("E-1", "ORD-999", "005930", OrderSide.BUY,
                1, new BigDecimal("70000"), NOW);

        assertThrows(IllegalArgumentException.class, () -> order.applyExecution(foreign));
    }

    @Test
    @DisplayName("취소된 주문은 다시 접수할 수 없다")
    void rejectsTransitionFromTerminalState() {
        Order cancelled = newOrder(10).accept(NOW).cancel(NOW.plusSeconds(1));

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertThrows(IllegalStateException.class, () -> cancelled.accept(NOW.plusSeconds(2)));
    }

    @Test
    @DisplayName("거부된 주문은 사유를 보관한다")
    void keepsRejectReason() {
        Order rejected = newOrder(10).reject("증거금 부족", NOW);

        assertEquals(OrderStatus.REJECTED, rejected.status());
        assertEquals("증거금 부족", rejected.rejectReasonIfPresent().orElseThrow());
        assertTrue(rejected.describe().contains("증거금 부족"));
    }

    @Test
    @DisplayName("시장가 주문에 가격을 지정하면 거부한다")
    void rejectsPricedMarketOrder() {
        assertThrows(IllegalArgumentException.class, () -> new OrderCommand(
                "005930", "삼성전자", OrderSide.BUY, OrderType.MARKET, 1, new BigDecimal("70000")));
    }

    @Test
    @DisplayName("지정가 OrderCommand를 생성한다")
    void createsLimitOrderCommand() {
        OrderCommand command = OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                3, new BigDecimal("70000"));

        assertEquals(OrderType.LIMIT, command.type());
        assertEquals(3L, command.quantity());
        assertEquals(0, new BigDecimal("210000").compareTo(command.estimatedAmount(null)));
    }
}
