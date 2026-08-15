package org.ossproject.fake;

import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.StockDetail;

import java.math.BigDecimal;
import java.time.Clock;

/** Deterministic latest-stock query adapter for UI development. */
public final class FakeStockQueryAdapter implements StockQueryPort {
    private final Clock clock;

    public FakeStockQueryAdapter() {
        this(Clock.systemDefaultZone());
    }

    public FakeStockQueryAdapter(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StockDetail getDetail(String symbol) {
        String effectiveSymbol = symbol == null || symbol.isBlank() ? "005930" : symbol;
        return new StockDetail(effectiveSymbol, "삼성전자", new BigDecimal("73500"),
                new BigDecimal("2300"), new BigDecimal("3.23"), PriceDirection.UP,
                new BigDecimal("71800"), new BigDecimal("75200"), new BigDecimal("70100"),
                18_450_230L, clock.instant());
    }
}
