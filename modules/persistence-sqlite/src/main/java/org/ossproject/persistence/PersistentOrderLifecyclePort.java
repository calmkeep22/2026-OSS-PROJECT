package org.ossproject.persistence;

import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.application.port.OrderRepository;
import org.ossproject.finance.model.order.Order;
import org.ossproject.finance.model.order.OrderCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 증권사 주문 포트를 원본으로 사용하면서 성공한 응답을 로컬 저장소에 기록한다.
 *
 * <p>주문 전송이 성공한 뒤 로컬 저장만 실패했을 때 호출자에게 실패를 던지면 사용자가
 * 같은 주문을 다시 보낼 수 있다. 따라서 원격 성공은 그대로 반환하고 저장 실패는 로그로
 * 남긴다. 반대로 주문 전송과 취소는 절대 로컬 결과로 대신하지 않는다.</p>
 */
public final class PersistentOrderLifecyclePort implements OrderLifecyclePort {
    private static final System.Logger LOGGER =
            System.getLogger(PersistentOrderLifecyclePort.class.getName());

    private final OrderLifecyclePort remote;
    private final OrderRepository local;

    public PersistentOrderLifecyclePort(OrderLifecyclePort remote, OrderRepository local) {
        this.remote = Objects.requireNonNull(remote, "원격 주문 포트는 필수입니다.");
        this.local = Objects.requireNonNull(local, "로컬 주문 저장소는 필수입니다.");
    }

    @Override public Order submit(OrderCommand command) {
        Order order = remote.submit(command);
        saveQuietly(order);
        return order;
    }

    @Override public Order cancel(String orderId) {
        Order order = remote.cancel(orderId);
        saveQuietly(order);
        return order;
    }

    @Override public Optional<Order> findOrder(String orderId) {
        try {
            Optional<Order> found = remote.findOrder(orderId);
            found.ifPresent(this::saveQuietly);
            return found.isPresent() ? found : local.findById(orderId);
        } catch (RuntimeException remoteFailure) {
            Optional<Order> cached = local.findById(orderId);
            if (cached.isPresent()) return cached;
            throw remoteFailure;
        }
    }

    @Override public List<Order> openOrders() {
        // 미체결 여부는 주문 가능 수량과 취소 판단에 영향을 주므로 오래된 캐시로 대신하지 않는다.
        List<Order> current = remote.openOrders();
        current.forEach(this::saveQuietly);
        return current;
    }

    @Override public List<Order> orders() {
        try {
            List<Order> current = remote.orders();
            current.forEach(this::saveQuietly);
            return merge(current, local.findAll());
        } catch (RuntimeException remoteFailure) {
            List<Order> cached = local.findAll();
            if (!cached.isEmpty()) return cached;
            throw remoteFailure;
        }
    }

    private void saveQuietly(Order order) {
        try {
            local.save(order);
        } catch (RuntimeException persistenceFailure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "주문 {0}의 로컬 기록을 저장하지 못했습니다: {1}",
                    order.orderId(), persistenceFailure.getMessage());
        }
    }

    private static List<Order> merge(List<Order> current, List<Order> history) {
        Map<String, Order> byId = new LinkedHashMap<>();
        for (Order order : current) byId.put(order.orderId(), order);
        for (Order order : history) byId.putIfAbsent(order.orderId(), order);
        return List.copyOf(new ArrayList<>(byId.values()));
    }
}
