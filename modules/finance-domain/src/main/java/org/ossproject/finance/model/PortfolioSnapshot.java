package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSnapshot(BigDecimal cash, List<Holding> holdings) {
    public PortfolioSnapshot { holdings = List.copyOf(holdings); }

    public BigDecimal totalMarketValue() {
        return holdings.stream().map(Holding::marketValue).reduce(cash, BigDecimal::add);
    }
}
