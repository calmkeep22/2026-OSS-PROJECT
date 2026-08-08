package org.ossproject.fake;

import org.ossproject.finance.model.*;
import org.ossproject.application.port.StockQueryPort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class FakeStockQueryAdapter implements StockQueryPort {
    @Override public StockDetail getDetail(String symbol) {
        return new StockDetail("005930", "삼성전자", new BigDecimal("73500"), new BigDecimal("2300"),
                new BigDecimal("3.23"), PriceDirection.UP, new BigDecimal("71800"),
                new BigDecimal("75200"), new BigDecimal("70100"), 18_450_230L, Instant.now());
    }

    @Override public List<PricePoint> getPriceHistory(String symbol, PricePeriod period) {
        LocalDate start = LocalDate.now().minusDays(9);
        return List.of(
                point(start, "70100", "71000", "69500", "70800", 8_120_000),
                point(start.plusDays(1), "70800", "72100", "70400", "71600", 9_430_000),
                point(start.plusDays(2), "71600", "71900", "69800", "70500", 7_980_000),
                point(start.plusDays(3), "70500", "72800", "70300", "72400", 10_210_000),
                point(start.plusDays(4), "72400", "73100", "71800", "72600", 9_840_000),
                point(start.plusDays(5), "72600", "73500", "72000", "73100", 11_560_000),
                point(start.plusDays(6), "73100", "73800", "72200", "72900", 12_100_000),
                point(start.plusDays(7), "72900", "74200", "72500", "73800", 13_880_000),
                point(start.plusDays(8), "73800", "75200", "70100", "73500", 18_450_230));
    }

    private PricePoint point(LocalDate date, String open, String high, String low, String close, long volume) {
        return new PricePoint(date, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), volume);
    }
}
