package org.ossproject.mocktrading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.application.port.OrderEventListener;
import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.order.Execution;
import org.ossproject.finance.model.order.Order;
import org.ossproject.finance.model.order.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.order.OrderStatus;
import org.ossproject.finance.model.account.Position;
import org.ossproject.finance.model.market.Quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockTradingEngineTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-08T01:00:00Z"), ZoneId.of("Asia/Seoul"));

    private MockTradingEngine engineWithCash(String cash, FillMode mode) {
        return new MockTradingEngine(Account.of("00000000001", new BigDecimal(cash)), mode, CLOCK);
    }

    private MockTradingEngine engineHolding(String cash, long quantity, FillMode mode) {
        Account account = new Account("00000000001",
                org.ossproject.finance.model.account.Balance.of(new BigDecimal(cash)),
                List.of(Position.of("005930", "삼성전자", quantity, new BigDecimal("70000"))));
        return new MockTradingEngine(account, mode, CLOCK);
    }

    private OrderCommand buy(long quantity, String price) {
        return OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, quantity, new BigDecimal(price));
    }

    private OrderCommand sell(long quantity, String price) {
        return OrderCommand.limit("005930", "삼성전자", OrderSide.SELL, quantity, new BigDecimal(price));
    }

    @Test
    @DisplayName("매수 주문을 접수하면 예수금은 그대로지만 주문가능금액이 줄어든다")
    void locksCashOnSubmit() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.MANUAL);

        Order order = engine.submit(buy(10, "70000"));

        assertEquals(OrderStatus.ACCEPTED, order.status());
        assertEquals(0, new BigDecimal("1000000").compareTo(engine.getAccount().balance().cash()));
        assertEquals(0, new BigDecimal("300000").compareTo(engine.getAccount().balance().available()));
    }

    @Test
    @DisplayName("주문가능금액이 부족하면 접수를 거부한다")
    void rejectsSubmitWhenCashInsufficient() {
        MockTradingEngine engine = engineWithCash("500000", FillMode.MANUAL);

        assertThrows(IllegalStateException.class, () -> engine.submit(buy(10, "70000")));
        assertEquals(0, new BigDecimal("500000").compareTo(engine.getAccount().balance().available()));
    }

    @Test
    @DisplayName("매수 체결 시 현금이 빠지고 보유 종목이 생긴다")
    void settlesBuyFill() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.IMMEDIATE);

        Order order = engine.submit(buy(10, "70000"));

        assertEquals(OrderStatus.FILLED, order.status());
        assertEquals(0, new BigDecimal("300000").compareTo(engine.getAccount().balance().cash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(engine.getAccount().balance().locked()));

        Position position = engine.getAccount().position("005930").orElseThrow();
        assertEquals(10L, position.quantity());
        assertEquals(0, new BigDecimal("70000.00").compareTo(position.averagePrice()));
    }

    @Test
    @DisplayName("지정가보다 싸게 체결되면 남은 예약 금액이 되돌아온다")
    void refundsUnusedReservation() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.MANUAL);
        Order order = engine.submit(buy(10, "70000"));

        engine.fill(order.orderId(), 10, new BigDecimal("69000"));

        // 700,000을 잡았지만 690,000만 썼다.
        assertEquals(0, new BigDecimal("310000").compareTo(engine.getAccount().balance().cash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(engine.getAccount().balance().locked()));
        assertEquals(0, new BigDecimal("310000").compareTo(engine.getAccount().balance().available()));
    }

    @Test
    @DisplayName("부분 체결이 잔고와 보유 수량에 순차로 반영된다")
    void settlesPartialFills() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.MANUAL);
        Order order = engine.submit(buy(10, "70000"));

        Order partial = engine.fill(order.orderId(), 4, new BigDecimal("70000"));
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(0, new BigDecimal("720000").compareTo(engine.getAccount().balance().cash()));
        assertEquals(4L, engine.getAccount().position("005930").orElseThrow().quantity());

        Order filled = engine.fill(order.orderId(), 6, new BigDecimal("70000"));
        assertEquals(OrderStatus.FILLED, filled.status());
        assertEquals(0, new BigDecimal("300000").compareTo(engine.getAccount().balance().cash()));
        assertEquals(10L, engine.getAccount().position("005930").orElseThrow().quantity());
    }

    @Test
    @DisplayName("주문을 취소하면 잡아 둔 금액이 모두 돌아온다")
    void releasesCashOnCancel() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.MANUAL);
        Order order = engine.submit(buy(10, "70000"));

        Order cancelled = engine.cancel(order.orderId());

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(0, new BigDecimal("1000000").compareTo(engine.getAccount().balance().available()));
    }

    @Test
    @DisplayName("부분 체결된 주문을 취소하면 남은 예약만 돌아온다")
    void releasesRemainderOnCancelAfterPartialFill() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.MANUAL);
        Order order = engine.submit(buy(10, "70000"));
        engine.fill(order.orderId(), 4, new BigDecimal("70000"));

        engine.cancel(order.orderId());

        // 720,000 남은 현금 전부가 다시 주문 가능해진다.
        assertEquals(0, new BigDecimal("720000").compareTo(engine.getAccount().balance().cash()));
        assertEquals(0, new BigDecimal("720000").compareTo(engine.getAccount().balance().available()));
    }

    @Test
    @DisplayName("매도 주문은 보유 수량을 묶고 체결되면 현금이 들어온다")
    void settlesSell() {
        MockTradingEngine engine = engineHolding("100000", 10, FillMode.MANUAL);

        Order order = engine.submit(sell(6, "75000"));
        Position locked = engine.getAccount().position("005930").orElseThrow();
        assertEquals(10L, locked.quantity());
        assertEquals(4L, locked.availableQuantity());

        engine.fill(order.orderId(), 6, new BigDecimal("75000"));

        Position after = engine.getAccount().position("005930").orElseThrow();
        assertEquals(4L, after.quantity());
        assertEquals(4L, after.availableQuantity());
        assertEquals(0, new BigDecimal("550000").compareTo(engine.getAccount().balance().cash()));
    }

    @Test
    @DisplayName("보유 수량보다 많이 매도할 수 없다")
    void rejectsOversell() {
        MockTradingEngine engine = engineHolding("100000", 10, FillMode.MANUAL);
        engine.submit(sell(8, "75000"));

        assertThrows(IllegalStateException.class, () -> engine.submit(sell(5, "75000")));
    }

    @Test
    @DisplayName("보유하지 않은 종목은 매도할 수 없다")
    void rejectsSellWithoutPosition() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.MANUAL);

        assertThrows(IllegalStateException.class, () -> engine.submit(sell(1, "75000")));
    }

    @Test
    @DisplayName("실시간 시세가 지정가를 지나가면 체결된다")
    void fillsOnQuoteCross() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.ON_QUOTE);
        Order order = engine.submit(buy(10, "70000"));

        engine.onQuote(Quote.of("005930", new BigDecimal("71000"), 1_000L, CLOCK.instant()));
        assertEquals(OrderStatus.ACCEPTED, engine.findOrder(order.orderId()).orElseThrow().status());

        engine.onQuote(Quote.of("005930", new BigDecimal("69500"), 2_000L, CLOCK.instant()));
        assertEquals(OrderStatus.FILLED, engine.findOrder(order.orderId()).orElseThrow().status());
        assertEquals(0, new BigDecimal("305000").compareTo(engine.getAccount().balance().cash()));
    }

    @Test
    @DisplayName("시장가 주문은 마지막 시세로 즉시 체결된다")
    void fillsMarketOrderAtLastPrice() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.ON_QUOTE);
        engine.seedPrice("005930", new BigDecimal("72000"));

        Order order = engine.submit(OrderCommand.market("005930", "삼성전자", OrderSide.BUY, 10));

        assertEquals(OrderStatus.FILLED, order.status());
        assertEquals(0, new BigDecimal("280000").compareTo(engine.getAccount().balance().cash()));
    }

    @Test
    @DisplayName("기준 시세가 없으면 시장가 주문을 받지 않는다")
    void rejectsMarketOrderWithoutPrice() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.ON_QUOTE);

        assertThrows(IllegalStateException.class,
                () -> engine.submit(OrderCommand.market("005930", "삼성전자", OrderSide.BUY, 10)));
    }

    @Test
    @DisplayName("주문 상태 변화와 체결이 리스너에 통지된다")
    void publishesEvents() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.IMMEDIATE);
        List<OrderStatus> statuses = new ArrayList<>();
        List<Execution> executions = new ArrayList<>();

        engine.addOrderEventListener(new OrderEventListener() {
            @Override
            public void onOrderUpdated(Order order) {
                statuses.add(order.status());
            }

            @Override
            public void onExecution(Execution execution) {
                executions.add(execution);
            }
        });

        engine.submit(buy(10, "70000"));

        assertEquals(List.of(OrderStatus.ACCEPTED, OrderStatus.FILLED), statuses);
        assertEquals(1, executions.size());
        assertEquals(10L, executions.get(0).quantity());
    }

    @Test
    @DisplayName("리스너가 예외를 던져도 주문 처리는 계속된다")
    void isolatesFailingListener() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.IMMEDIATE);
        List<OrderStatus> received = new ArrayList<>();

        engine.addOrderEventListener(order -> {
            throw new IllegalStateException("화면 갱신 실패");
        });
        engine.addOrderEventListener(order -> received.add(order.status()));

        Order order = engine.submit(buy(10, "70000"));

        assertEquals(OrderStatus.FILLED, order.status());
        assertEquals(List.of(OrderStatus.ACCEPTED, OrderStatus.FILLED), received);
    }

    @Test
    @DisplayName("미체결 주문과 전체 주문을 최근 접수 순으로 돌려준다")
    void listsOrders() {
        MockTradingEngine engine = engineWithCash("10000000", FillMode.MANUAL);
        Order first = engine.submit(buy(1, "70000"));
        Order second = engine.submit(buy(2, "70000"));
        engine.cancel(first.orderId());

        assertEquals(2, engine.orders().size());
        assertEquals(second.orderId(), engine.orders().get(0).orderId());
        assertEquals(1, engine.openOrders().size());
        assertEquals(second.orderId(), engine.openOrders().get(0).orderId());
    }

    @Test
    @DisplayName("종료된 주문은 취소할 수 없다")
    void rejectsCancelOnTerminalOrder() {
        MockTradingEngine engine = engineWithCash("1000000", FillMode.IMMEDIATE);
        Order order = engine.submit(buy(10, "70000"));

        assertThrows(IllegalStateException.class, () -> engine.cancel(order.orderId()));
    }

    @Test
    @DisplayName("실시간 시세가 보유 종목 평가액에 반영된다")
    void appliesQuoteToPositions() {
        MockTradingEngine engine = engineHolding("100000", 10, FillMode.MANUAL);

        engine.onQuote(Quote.of("005930", new BigDecimal("75000"), 1_000L, CLOCK.instant()));

        assertEquals(0, new BigDecimal("750000").compareTo(engine.getAccount().totalMarketValue()));
        assertTrue(engine.lastPrice("005930").isPresent());
    }
}
