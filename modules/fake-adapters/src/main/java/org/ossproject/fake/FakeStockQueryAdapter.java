package org.ossproject.fake;

import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.market.SecuritySummary;
import org.ossproject.finance.model.market.StockDetail;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic security search and detail adapter for UI development. */
public final class FakeStockQueryAdapter implements StockQueryPort {
    private final Clock clock;

    public FakeStockQueryAdapter() {
        this(Clock.systemDefaultZone());
    }

    public FakeStockQueryAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<SecuritySummary> search(String query, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be positive.");
        }
        return FakeSecurityUniverse.all().stream()
                .map(FakeSecurityUniverse.FakeSecurity::toSummary)
                .filter(summary -> summary.matches(query))
                .sorted(Comparator.comparing(SecuritySummary::symbol))
                .limit(limit)
                .toList();
    }

    @Override
    public StockDetail getDetail(String symbol) {
        FakeSecurityUniverse.FakeSecurity security = FakeSecurityUniverse.find(symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown security: " + symbol));
        return new StockDetail(security.symbol(), security.name(), security.currentPrice(),
                security.changeAmount(), security.changeRate(), security.direction(),
                security.open(), security.high(), security.low(), security.volume(),
                clock.instant());
    }
}
