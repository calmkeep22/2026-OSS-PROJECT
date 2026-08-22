package org.ossproject.finance.model.order;

import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 개별 체결 한 건.
 *
 * <p>하나의 주문은 여러 번에 나뉘어 체결될 수 있으므로 주문과 체결은 1:N 이다.
 */
public record Execution(
        String executionId,
        String orderId,
        String symbol,
        OrderSide side,
        long quantity,
        BigDecimal price,
        Instant executedAt
) {
    public Execution {
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("체결 번호는 필수입니다.");
        }
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("주문 번호는 필수입니다.");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (side == null) {
            throw new IllegalArgumentException("주문 구분은 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("체결 수량은 1 이상이어야 합니다.");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("체결 가격은 0보다 커야 합니다.");
        }
        if (executedAt == null) {
            throw new IllegalArgumentException("체결 시각은 필수입니다.");
        }
    }

    /** 이 체결의 거래 대금. */
    public BigDecimal amount() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
