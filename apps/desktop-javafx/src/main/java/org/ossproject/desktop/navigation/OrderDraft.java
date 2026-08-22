package org.ossproject.desktop.navigation;

import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.order.OrderType;

import java.util.Objects;

/** 종목 화면에서 주문 화면으로 전달하는 사용자 의도. */
public record OrderDraft(
        String symbol,
        String name,
        OrderSide side,
        OrderType type,
        int quantity,
        String price,
        Screen origin
) {
    public OrderDraft {
        symbol = requireText(symbol, "종목 코드");
        name = requireText(name, "종목명");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        if (quantity <= 0) throw new IllegalArgumentException("주문 수량은 1 이상이어야 합니다.");
        price = requireText(price, "주문 가격");
        Objects.requireNonNull(origin, "origin");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "은(는) 필수입니다.");
        return value.trim();
    }
}
