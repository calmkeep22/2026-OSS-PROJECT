package org.ossproject.finance.model.order;

/** 주문 유형. */
public enum OrderType {
    /** 지정가. {@code limitPrice} 가 반드시 있어야 한다. */
    LIMIT("지정가"),
    /** 시장가. {@code limitPrice} 는 비어 있다. */
    MARKET("시장가");

    private final String displayName;

    OrderType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
