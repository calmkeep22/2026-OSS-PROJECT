package org.ossproject.finance.model;

import java.math.BigDecimal;

public record OrderRequest(String symbol, String name, OrderSide side, long quantity, BigDecimal limitPrice) {
    public OrderRequest {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("종목 코드는 필수입니다.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("종목명은 필수입니다.");
        if (side == null) throw new IllegalArgumentException("주문 구분은 필수입니다.");
        if (quantity <= 0) throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        if (limitPrice == null || limitPrice.signum() <= 0) throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
    }
    public BigDecimal estimatedAmount() { return limitPrice.multiply(BigDecimal.valueOf(quantity)); }
}
