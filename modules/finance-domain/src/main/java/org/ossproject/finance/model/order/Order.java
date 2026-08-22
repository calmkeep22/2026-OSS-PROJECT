package org.ossproject.finance.model.order;

import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 생명주기를 가진 주문.
 *
 * <p>불변 객체이며 모든 상태 변화는 새 인스턴스를 반환한다. 허용되지 않는 전이는
 * {@link IllegalStateException} 으로 막는다.
 */
public record Order(
        String orderId,
        String symbol,
        String name,
        OrderSide side,
        OrderType type,
        long quantity,
        BigDecimal limitPrice,
        OrderStatus status,
        long filledQuantity,
        BigDecimal filledAmount,
        List<Execution> executions,
        String rejectReason,
        Instant createdAt,
        Instant updatedAt
) {
    public Order {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("주문 번호는 필수입니다.");
        }
        if (status == null) {
            throw new IllegalArgumentException("주문 상태는 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
        if (filledQuantity < 0 || filledQuantity > quantity) {
            throw new IllegalArgumentException("체결 수량이 주문 수량 범위를 벗어났습니다.");
        }
        executions = List.copyOf(executions == null ? List.of() : executions);
        filledAmount = filledAmount == null ? BigDecimal.ZERO : filledAmount;
    }

    /** 접수 전(NEW) 상태의 새 주문을 만든다. */
    public static Order create(String orderId, OrderCommand command, Instant now) {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        if (now == null) {
            throw new IllegalArgumentException("생성 시각은 필수입니다.");
        }
        return new Order(orderId, command.symbol(), command.name(), command.side(), command.type(),
                command.quantity(), command.limitPrice(), OrderStatus.NEW,
                0L, BigDecimal.ZERO, List.of(), null, now, now);
    }

    /** 아직 체결되지 않은 수량. */
    public long remainingQuantity() {
        return quantity - filledQuantity;
    }

    /** 체결 평균 단가. 체결이 없으면 {@link BigDecimal#ZERO}. */
    public BigDecimal averageFilledPrice() {
        if (filledQuantity == 0L) {
            return BigDecimal.ZERO;
        }
        return filledAmount.divide(BigDecimal.valueOf(filledQuantity), 2, RoundingMode.HALF_UP);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public Optional<String> rejectReasonIfPresent() {
        return Optional.ofNullable(rejectReason);
    }

    /** 증권사 접수 완료. */
    public Order accept(Instant now) {
        return transition(OrderStatus.ACCEPTED, rejectReason, now);
    }

    /** 증권사 거부. */
    public Order reject(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("거부 사유는 필수입니다.");
        }
        return transition(OrderStatus.REJECTED, reason, now);
    }

    /** 주문 취소. */
    public Order cancel(Instant now) {
        return transition(OrderStatus.CANCELLED, rejectReason, now);
    }

    /**
     * 체결 한 건을 반영한다.
     *
     * <p>남은 수량을 초과하는 체결, 이미 종료된 주문에 대한 체결, 다른 주문의 체결은 거부한다.
     */
    public Order applyExecution(Execution execution) {
        if (execution == null) {
            throw new IllegalArgumentException("체결 정보는 필수입니다.");
        }
        if (!orderId.equals(execution.orderId())) {
            throw new IllegalArgumentException(
                    "다른 주문의 체결입니다. 주문 " + orderId + ", 체결 대상 " + execution.orderId());
        }
        if (isTerminal()) {
            throw new IllegalStateException(
                    "이미 종료된 주문에는 체결을 반영할 수 없습니다. 현재 상태 " + status.displayName());
        }
        if (execution.quantity() > remainingQuantity()) {
            throw new IllegalArgumentException(
                    "체결 수량이 남은 수량을 초과합니다. 남은 수량 " + remainingQuantity()
                            + ", 체결 수량 " + execution.quantity());
        }

        long newFilledQuantity = filledQuantity + execution.quantity();
        BigDecimal newFilledAmount = filledAmount.add(execution.amount());
        OrderStatus newStatus = newFilledQuantity == quantity
                ? OrderStatus.FILLED
                : OrderStatus.PARTIALLY_FILLED;

        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    "허용되지 않는 상태 전이입니다. " + status.displayName() + " → " + newStatus.displayName());
        }

        List<Execution> newExecutions = new ArrayList<>(executions);
        newExecutions.add(execution);

        return new Order(orderId, symbol, name, side, type, quantity, limitPrice, newStatus,
                newFilledQuantity, newFilledAmount, newExecutions, rejectReason,
                createdAt, execution.executedAt());
    }

    private Order transition(OrderStatus next, String newRejectReason, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("변경 시각은 필수입니다.");
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "허용되지 않는 상태 전이입니다. " + status.displayName() + " → " + next.displayName());
        }
        return new Order(orderId, symbol, name, side, type, quantity, limitPrice, next,
                filledQuantity, filledAmount, executions, newRejectReason, createdAt, now);
    }

    /** 음성 안내에 그대로 읽어 줄 수 있는 한 줄 요약. */
    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append(name).append(' ')
                .append(quantity).append("주 ")
                .append(side.displayName()).append(' ')
                .append(type.displayName()).append(" 주문, ")
                .append(status.displayName());
        if (filledQuantity > 0 && status != OrderStatus.FILLED) {
            sb.append(", ").append(filledQuantity).append("주 체결");
        }
        if (rejectReason != null) {
            sb.append(", 사유 ").append(rejectReason);
        }
        return sb.toString();
    }
}
