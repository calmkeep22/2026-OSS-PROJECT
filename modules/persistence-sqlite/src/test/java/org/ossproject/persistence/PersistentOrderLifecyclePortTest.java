package org.ossproject.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.order.Order;
import org.ossproject.finance.model.order.OrderCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistentOrderLifecyclePortTest {
    private SqliteDatabase database;
    private SqliteOrderRepository repository;
    private StubRemote remote;
    private PersistentOrderLifecyclePort port;

    @BeforeEach void setUp() {
        database = SqliteDatabase.openInMemory();
        repository = new SqliteOrderRepository(database);
        remote = new StubRemote();
        port = new PersistentOrderLifecyclePort(remote, repository);
    }

    @AfterEach void tearDown() {
        database.close();
    }

    @Test void savesSuccessfulSubmission() {
        Order submitted = port.submit(command());

        assertEquals(submitted, repository.findById(submitted.orderId()).orElseThrow());
    }

    @Test void returnsCachedHistoryWhenRemoteHistoryFails() {
        Order saved = remote.submit(command());
        repository.save(saved);
        remote.failure = new IllegalStateException("연결 끊김");

        assertEquals(List.of(saved), port.orders());
    }

    @Test void doesNotUseStaleCacheForOpenOrders() {
        repository.save(remote.submit(command()));
        remote.failure = new IllegalStateException("연결 끊김");

        assertThrows(IllegalStateException.class, port::openOrders);
    }

    private static OrderCommand command() {
        return OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                2, new BigDecimal("70000"));
    }

    private static final class StubRemote implements OrderLifecyclePort {
        private final List<Order> orders = new ArrayList<>();
        private RuntimeException failure;

        @Override public Order submit(OrderCommand command) {
            if (failure != null) throw failure;
            Order order = Order.create("ORD-" + (orders.size() + 1), command,
                    Instant.parse("2026-08-23T01:00:00Z")).accept(
                    Instant.parse("2026-08-23T01:00:01Z"));
            orders.add(order);
            return order;
        }

        @Override public Order cancel(String orderId) {
            if (failure != null) throw failure;
            Order cancelled = findOrder(orderId).orElseThrow().cancel(
                    Instant.parse("2026-08-23T01:01:00Z"));
            orders.removeIf(order -> order.orderId().equals(orderId));
            orders.add(cancelled);
            return cancelled;
        }

        @Override public Optional<Order> findOrder(String orderId) {
            if (failure != null) throw failure;
            return orders.stream().filter(order -> order.orderId().equals(orderId)).findFirst();
        }

        @Override public List<Order> openOrders() {
            if (failure != null) throw failure;
            return orders.stream().filter(order -> !order.isTerminal()).toList();
        }

        @Override public List<Order> orders() {
            if (failure != null) throw failure;
            return List.copyOf(orders);
        }
    }
}
