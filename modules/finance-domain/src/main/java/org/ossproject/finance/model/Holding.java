package org.ossproject.finance.model;

import java.math.BigDecimal;

public record Holding(
        String symbol,
        String name,
        long quantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice
) {
    public BigDecimal marketValue() {
        return currentPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal profitLoss() {
        return currentPrice.subtract(averagePrice).multiply(BigDecimal.valueOf(quantity));
    }
}
