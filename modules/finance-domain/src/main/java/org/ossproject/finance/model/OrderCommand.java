package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 주문 접수 요청. 지정가와 시장가를 모두 표현한다.
 *
 * <p>기존 {@link OrderRequest} 는 지정가만 표현할 수 있어 그대로 두고,
 * 생명주기를 다루는 계층에서는 이 타입을 사용한다.
 * {@link #from(OrderRequest)} 로 상호 변환할 수 있다.
 */
public record OrderCommand(
        String symbol,
        String name,
        OrderSide side,
        OrderType type,
        long quantity,
        BigDecimal limitPrice
) {
    public OrderCommand {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("종목명은 필수입니다.");
        }
        if (side == null) {
            throw new IllegalArgumentException("주문 구분은 필수입니다.");
        }
        if (type == null) {
            throw new IllegalArgumentException("주문 유형은 필수입니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("지정가 주문은 0보다 큰 가격이 필요합니다.");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("시장가 주문에는 가격을 지정할 수 없습니다.");
        }
    }

    /** 지정가 주문을 만든다. */
    public static OrderCommand limit(String symbol, String name, OrderSide side,
                                     long quantity, BigDecimal limitPrice) {
        return new OrderCommand(symbol, name, side, OrderType.LIMIT, quantity, limitPrice);
    }

    /** 시장가 주문을 만든다. */
    public static OrderCommand market(String symbol, String name, OrderSide side, long quantity) {
        return new OrderCommand(symbol, name, side, OrderType.MARKET, quantity, null);
    }

    /** 기존 {@link OrderRequest}(지정가) 를 변환한다. */
    public static OrderCommand from(OrderRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        return limit(request.symbol(), request.name(), request.side(),
                request.quantity(), request.limitPrice());
    }

    public Optional<BigDecimal> limitPriceIfPresent() {
        return Optional.ofNullable(limitPrice);
    }

    /**
     * 잔고 확인과 주문 한도 검증에 쓰는 예상 주문 금액.
     *
     * @param referencePrice 시장가 주문일 때 사용할 기준 가격(현재가)
     */
    public BigDecimal estimatedAmount(BigDecimal referencePrice) {
        BigDecimal unit = limitPrice != null ? limitPrice : referencePrice;
        if (unit == null || unit.signum() <= 0) {
            throw new IllegalArgumentException("시장가 주문 금액을 계산하려면 기준 가격이 필요합니다.");
        }
        return unit.multiply(BigDecimal.valueOf(quantity));
    }
}
