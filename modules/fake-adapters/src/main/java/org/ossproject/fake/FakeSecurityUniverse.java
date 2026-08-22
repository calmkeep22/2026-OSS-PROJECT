package org.ossproject.fake;

import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.market.SecuritySummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The securities every fake adapter shares.
 *
 * <p>Both the stock-detail and candle adapters read from this table so a security shows the same
 * price everywhere. Without a shared source the detail screen and the chart would disagree, and
 * the screen would be tempted to invent numbers to reconcile them.
 *
 * <p>Values are fixed rather than random so tests and screen-reader output stay reproducible.
 */
final class FakeSecurityUniverse {

    private static final Map<String, FakeSecurity> SECURITIES = securities();

    private FakeSecurityUniverse() {
    }

    static Optional<FakeSecurity> find(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SECURITIES.get(symbol.strip().toUpperCase(java.util.Locale.ROOT)));
    }

    static List<FakeSecurity> all() {
        return List.copyOf(SECURITIES.values());
    }

    private static Map<String, FakeSecurity> securities() {
        Map<String, FakeSecurity> map = new LinkedHashMap<>();
        put(map, new FakeSecurity("005930", "삼성전자", "국내", "KRX", "KRW",
                "73500", "71800", "75200", "70100", "2300", "3.23", 18_450_230L));
        put(map, new FakeSecurity("000660", "SK하이닉스", "국내", "KRX", "KRW",
                "184500", "182000", "186900", "181200", "2580", "1.42", 5_821_330L));
        put(map, new FakeSecurity("035420", "NAVER", "국내", "KRX", "KRW",
                "205000", "206500", "207800", "204100", "-1460", "-0.71", 1_230_922L));
        put(map, new FakeSecurity("069500", "KODEX 200", "ETF", "KRX", "KRW",
                "36120", "35760", "36340", "35690", "365", "1.02", 3_142_880L));
        put(map, new FakeSecurity("AAPL", "Apple", "미국", "NASDAQ", "USD",
                "228.40", "226.55", "229.80", "226.10", "1.88", "0.83", 42_381_210L));
        put(map, new FakeSecurity("NVDA", "NVIDIA", "미국", "NASDAQ", "USD",
                "142.65", "139.80", "143.20", "139.05", "3.26", "2.34", 51_204_770L));
        return Map.copyOf(map);
    }

    private static void put(Map<String, FakeSecurity> map, FakeSecurity security) {
        map.put(security.symbol().toUpperCase(java.util.Locale.ROOT), security);
    }

    /** One security with a fixed latest snapshot. */
    record FakeSecurity(
            String symbol,
            String name,
            String market,
            String exchange,
            String currency,
            BigDecimal currentPrice,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal changeAmount,
            BigDecimal changeRate,
            long volume
    ) {
        FakeSecurity(String symbol, String name, String market, String exchange, String currency,
                     String currentPrice, String open, String high, String low,
                     String changeAmount, String changeRate, long volume) {
            this(symbol, name, market, exchange, currency,
                    new BigDecimal(currentPrice), new BigDecimal(open), new BigDecimal(high),
                    new BigDecimal(low), new BigDecimal(changeAmount), new BigDecimal(changeRate),
                    volume);
        }

        PriceDirection direction() {
            int signum = changeAmount.signum();
            if (signum > 0) {
                return PriceDirection.UP;
            }
            return signum < 0 ? PriceDirection.DOWN : PriceDirection.FLAT;
        }

        /** Decimal places used by this security's currency. */
        int scale() {
            return "KRW".equals(currency) ? 0 : 2;
        }

        SecuritySummary toSummary() {
            return new SecuritySummary(symbol, name, market, exchange, currency,
                    currentPrice, changeRate, direction());
        }

        /**
         * Price of the candle {@code stepsBack} steps before the latest one.
         *
         * <p>A fixed cosine walk keeps the series shaped like a chart while staying deterministic.
         * The latest step returns exactly {@link #currentPrice()} so the chart and the detail
         * screen agree without any rescaling.
         */
        BigDecimal priceAt(int stepsBack) {
            if (stepsBack <= 0) {
                return currentPrice;
            }
            double seed = Math.abs(symbol.hashCode() % 37) / 37.0;
            double swing = Math.cos((stepsBack * 0.45) + seed * Math.PI) * 0.035
                    + Math.cos(stepsBack * 0.13) * 0.015;
            BigDecimal factor = BigDecimal.valueOf(1.0 - (stepsBack * 0.0016) + swing);
            BigDecimal price = currentPrice.multiply(factor).setScale(scale(), RoundingMode.HALF_UP);
            BigDecimal floor = currentPrice.movePointLeft(1);
            return price.compareTo(floor) < 0 ? floor : price;
        }
    }
}
