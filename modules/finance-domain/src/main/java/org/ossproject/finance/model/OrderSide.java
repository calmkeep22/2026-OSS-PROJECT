package org.ossproject.finance.model;

public enum OrderSide {
    BUY("매수"), SELL("매도");
    private final String displayName;
    OrderSide(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
