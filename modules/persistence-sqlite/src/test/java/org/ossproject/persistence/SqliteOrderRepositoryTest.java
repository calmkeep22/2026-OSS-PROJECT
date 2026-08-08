package org.ossproject.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.finance.model.Execution;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.OrderType;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteOrderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:00:00Z");

    private SqliteDatabase database;
    private SqliteOrderRepository repository;

    @BeforeEach
    void setUp() {
        database = SqliteDatabase.openInMemory();
        repository = new SqliteOrderRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private Order buyOrder(String orderId, long quantity) {
        return Order.create(orderId,
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, quantity, new BigDecimal("70000")),
                NOW);
    }

    @Test
    @DisplayName("주문을 저장하고 그대로 읽어 온다")
    void savesAndLoadsOrder() {
        Order order = buyOrder("ORD-1", 10).accept(NOW);

        repository.save(order);
        Order loaded = repository.findById("ORD-1").orElseThrow();

        assertEquals(order.orderId(), loaded.orderId());
        assertEquals(OrderStatus.ACCEPTED, loaded.status());
        assertEquals(10L, loaded.quantity());
        assertEquals(0, new BigDecimal("70000").compareTo(loaded.limitPrice()));
        assertEquals(order.createdAt(), loaded.createdAt());
    }

    @Test
    @DisplayName("체결까지 함께 저장하고 복원한다")
    void savesExecutions() {
        Order order = buyOrder("ORD-2", 10).accept(NOW)
                .applyExecution(new Execution("E-1", "ORD-2", "005930", OrderSide.BUY,
                        4, new BigDecimal("70000"), NOW.plusSeconds(1)))
                .applyExecution(new Execution("E-2", "ORD-2", "005930", OrderSide.BUY,
                        6, new BigDecimal("71000"), NOW.plusSeconds(2)));

        repository.save(order);
        Order loaded = repository.findById("ORD-2").orElseThrow();

        assertEquals(OrderStatus.FILLED, loaded.status());
        assertEquals(2, loaded.executions().size());
        assertEquals("E-1", loaded.executions().get(0).executionId());
        assertEquals(0, new BigDecimal("70600.00").compareTo(loaded.averageFilledPrice()));
    }

    @Test
    @DisplayName("같은 주문을 다시 저장하면 상태가 갱신된다")
    void updatesExistingOrder() {
        Order order = buyOrder("ORD-3", 10).accept(NOW);
        repository.save(order);

        Order filled = order.applyExecution(new Execution("E-1", "ORD-3", "005930", OrderSide.BUY,
                10, new BigDecimal("70000"), NOW.plusSeconds(5)));
        repository.save(filled);

        Order loaded = repository.findById("ORD-3").orElseThrow();
        assertEquals(OrderStatus.FILLED, loaded.status());
        assertEquals(1, loaded.executions().size());
        assertEquals(1, repository.findAll().size());
    }

    @Test
    @DisplayName("금액은 소수점 오차 없이 그대로 보존된다")
    void preservesDecimalPrecision() {
        Order order = Order.create("ORD-4",
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 3, new BigDecimal("70123.45")),
                NOW).accept(NOW);

        repository.save(order);
        Order loaded = repository.findById("ORD-4").orElseThrow();

        assertEquals("70123.45", loaded.limitPrice().toPlainString());
    }

    @Test
    @DisplayName("시장가 주문은 지정가가 비어 있는 채로 복원된다")
    void handlesMarketOrder() {
        Order order = Order.create("ORD-5",
                OrderCommand.market("005930", "삼성전자", OrderSide.BUY, 5), NOW).accept(NOW);

        repository.save(order);
        Order loaded = repository.findById("ORD-5").orElseThrow();

        assertEquals(OrderType.MARKET, loaded.type());
        assertNull(loaded.limitPrice());
    }

    @Test
    @DisplayName("미체결 주문만 골라 온다")
    void findsOpenOrders() {
        repository.save(buyOrder("ORD-6", 1).accept(NOW));
        repository.save(buyOrder("ORD-7", 1).accept(NOW).cancel(NOW.plusSeconds(1)));
        repository.save(buyOrder("ORD-8", 1).accept(NOW)
                .applyExecution(new Execution("E-1", "ORD-8", "005930", OrderSide.BUY,
                        1, new BigDecimal("70000"), NOW.plusSeconds(1))));

        List<Order> open = repository.findOpen();

        assertEquals(1, open.size());
        assertEquals("ORD-6", open.get(0).orderId());
    }

    @Test
    @DisplayName("거부 사유를 보존한다")
    void keepsRejectReason() {
        repository.save(buyOrder("ORD-9", 1).reject("증거금 부족", NOW));

        Order loaded = repository.findById("ORD-9").orElseThrow();

        assertEquals(OrderStatus.REJECTED, loaded.status());
        assertEquals("증거금 부족", loaded.rejectReasonIfPresent().orElseThrow());
    }

    @Test
    @DisplayName("종목별로 조회한다")
    void findsBySymbol() {
        repository.save(buyOrder("ORD-10", 1).accept(NOW));
        repository.save(Order.create("ORD-11",
                OrderCommand.limit("000660", "SK하이닉스", OrderSide.BUY, 1, new BigDecimal("190000")),
                NOW).accept(NOW));

        assertEquals(1, repository.findBySymbol("000660").size());
        assertEquals("ORD-11", repository.findBySymbol("000660").get(0).orderId());
        assertTrue(repository.findBySymbol("999999").isEmpty());
    }

    @Test
    @DisplayName("보존 기간이 지난 주문을 지우면 체결도 함께 지워진다")
    void deletesOldOrdersWithExecutions() {
        Order old = Order.create("ORD-12",
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 1, new BigDecimal("70000")),
                NOW.minus(Duration.ofDays(400))).accept(NOW.minus(Duration.ofDays(400)));
        old = old.applyExecution(new Execution("E-9", "ORD-12", "005930", OrderSide.BUY,
                1, new BigDecimal("70000"), NOW.minus(Duration.ofDays(400))));
        repository.save(old);
        repository.save(buyOrder("ORD-13", 1).accept(NOW));

        int deleted = repository.deleteCreatedBefore(NOW.minus(Duration.ofDays(365)));

        assertEquals(1, deleted);
        assertTrue(repository.findById("ORD-12").isEmpty());
        assertEquals(1, repository.findAll().size());
    }

    @Test
    @DisplayName("파일 데이터베이스는 다시 열어도 내용이 남아 있다")
    void persistsAcrossReopen(@TempDir Path tempDir) {
        Path file = tempDir.resolve("orders.db");

        try (SqliteDatabase first = SqliteDatabase.open(file)) {
            new SqliteOrderRepository(first).save(buyOrder("ORD-14", 7).accept(NOW));
        }

        try (SqliteDatabase second = SqliteDatabase.open(file)) {
            Order loaded = new SqliteOrderRepository(second).findById("ORD-14").orElseThrow();
            assertEquals(7L, loaded.quantity());
        }
    }

    @Test
    @DisplayName("같은 파일을 여러 번 열어도 스키마 준비가 안전하게 반복된다")
    void migrationIsIdempotent(@TempDir Path tempDir) {
        Path file = tempDir.resolve("orders.db");

        for (int i = 0; i < 3; i++) {
            try (SqliteDatabase db = SqliteDatabase.open(file)) {
                new SqliteOrderRepository(db).save(buyOrder("ORD-15", 1).accept(NOW));
            }
        }

        try (SqliteDatabase db = SqliteDatabase.open(file)) {
            assertEquals(1, new SqliteOrderRepository(db).findAll().size());
        }
    }
}
