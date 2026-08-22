package org.ossproject.finance.model.market;
public enum PricePeriod {
    DAY("1일"), WEEK("1주"), MONTH("1개월"), THREE_MONTHS("3개월"), YEAR("1년");
    private final String displayName;
    PricePeriod(String displayName) { this.displayName = displayName; }
    public String displayName() { return displayName; }
}
