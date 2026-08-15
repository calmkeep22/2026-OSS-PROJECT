package org.ossproject.desktop.viewmodel;

import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/** 선택 종목을 상세 화면·차트·주문에서 사용할 표시 모델로 변환한다. */
public final class StockDetailViewModel {
    public enum ChartRange {
        MINUTE_1("1분", PricePeriod.DAY, "0.08"),
        MINUTE_5("5분", PricePeriod.DAY, "0.14"),
        MINUTE_30("30분", PricePeriod.DAY, "0.25"),
        DAY("일", PricePeriod.MONTH, "1.00"),
        WEEK("주", PricePeriod.THREE_MONTHS, "1.40"),
        MONTH("월", PricePeriod.YEAR, "2.00"),
        YEAR("년", PricePeriod.YEAR, "2.70");

        private final String label;
        private final PricePeriod queryPeriod;
        private final BigDecimal range;

        ChartRange(String label, PricePeriod queryPeriod, String range) {
            this.label = label; this.queryPeriod = queryPeriod; this.range = new BigDecimal(range);
        }

        public String label() { return label; }
    }

    private final DesktopSession session;
    private final StockQueryPort stockQuery;
    private final CandleQueryPort candleQuery;

    public StockDetailViewModel(DesktopSession session, StockQueryPort stockQuery,
                                CandleQueryPort candleQuery) {
        this.session = Objects.requireNonNull(session, "session");
        this.stockQuery = Objects.requireNonNull(stockQuery, "stockQuery");
        this.candleQuery = Objects.requireNonNull(candleQuery, "candleQuery");
    }

    public StockSelection selection() { return session.selectedStock(); }

    public StockDetail detail() {
        StockSelection selected = selection();
        BigDecimal current = parseNumber(selected.displayPrice(), new BigDecimal("73500"));
        BigDecimal signedRate = parseSignedNumber(selected.displayChange(), new BigDecimal("3.23"));
        PriceDirection direction = signedRate.signum() > 0 ? PriceDirection.UP
                : signedRate.signum() < 0 ? PriceDirection.DOWN : PriceDirection.FLAT;
        BigDecimal absoluteRate = signedRate.abs();
        BigDecimal change = current.multiply(absoluteRate).divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        int scale = selected.overseas() ? 2 : 0;
        return new StockDetail(selected.symbol(), selected.name(), current.setScale(scale, RoundingMode.HALF_UP),
                change.setScale(scale, RoundingMode.HALF_UP), absoluteRate.setScale(2, RoundingMode.HALF_UP), direction,
                current.multiply(new BigDecimal("0.986")).setScale(scale, RoundingMode.HALF_UP),
                current.multiply(new BigDecimal("1.018")).setScale(scale, RoundingMode.HALF_UP),
                current.multiply(new BigDecimal("0.972")).setScale(scale, RoundingMode.HALF_UP),
                selected.overseas() ? 42_381_210L : 18_450_230L,
                stockQuery.getDetail(selected.symbol()).updatedAt());
    }

    public List<PricePoint> history(PricePeriod period) {
        BigDecimal range = switch (period) {
            case DAY -> new BigDecimal("0.18");
            case WEEK -> new BigDecimal("0.45");
            case MONTH -> BigDecimal.ONE;
            case THREE_MONTHS -> new BigDecimal("1.65");
            case YEAR -> new BigDecimal("2.70");
        };
        return history(period, range);
    }

    public List<PricePoint> history(ChartRange range) {
        return history(range.queryPeriod, range.range);
    }

    private List<PricePoint> history(PricePeriod period, BigDecimal range) {
        int count = switch (period) {
            case DAY -> 2;
            case WEEK -> 7;
            case MONTH -> 30;
            case THREE_MONTHS -> 90;
            case YEAR -> 365;
        };
        List<PricePoint> base = candleQuery.getCandles(
                        selection().symbol(), CandleInterval.DAY, count).stream()
                .map(candle -> candle.toPricePoint(java.time.ZoneId.of("Asia/Seoul")))
                .toList();
        if (base.isEmpty()) return base;
        BigDecimal target = detail().currentPrice();
        BigDecimal factor = target.divide(base.get(base.size() - 1).close(), 10, RoundingMode.HALF_UP);
        int scale = selection().overseas() ? 2 : 0;
        return base.stream().map(point -> new PricePoint(point.date(),
                expand(scale(point.open(), factor, scale), target, range, scale),
                expand(scale(point.high(), factor, scale), target, range, scale),
                expand(scale(point.low(), factor, scale), target, range, scale),
                expand(scale(point.close(), factor, scale), target, range, scale), point.volume())).toList();
    }

    public String formatPrice(BigDecimal value) {
        if (selection().overseas()) return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return String.format("%,d원", value.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    public String plainOrderPrice() {
        return detail().currentPrice().stripTrailingZeros().toPlainString();
    }

    private BigDecimal scale(BigDecimal value, BigDecimal factor, int scale) {
        return value.multiply(factor).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal expand(BigDecimal value, BigDecimal center, BigDecimal range, int scale) {
        return center.add(value.subtract(center).multiply(range)).setScale(scale, RoundingMode.HALF_UP);
    }

    private BigDecimal parseNumber(String text, BigDecimal fallback) {
        if (text == null) return fallback;
        String normalized = text.replaceAll("[^0-9.]", "");
        try { return normalized.isBlank() ? fallback : new BigDecimal(normalized); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private BigDecimal parseSignedNumber(String text, BigDecimal fallback) {
        if (text == null) return fallback;
        String normalized = text.replaceAll("[^0-9.+-]", "");
        try { return normalized.isBlank() ? fallback : new BigDecimal(normalized); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
