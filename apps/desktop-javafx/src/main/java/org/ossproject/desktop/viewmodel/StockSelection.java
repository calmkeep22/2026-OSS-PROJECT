package org.ossproject.desktop.viewmodel;

import org.ossproject.finance.model.market.SecuritySummary;
import org.ossproject.finance.model.SecurityId;

import java.util.Objects;

/**
 * 현재 화면과 주문 화면이 함께 사용하는 선택 종목.
 *
 * <p>가격을 담지 않는다. 선택은 "어떤 종목을 보고 있는가"만 나타내고, 값은 항상
 * {@code StockQueryPort} 로 다시 조회한다. 예전에는 화면 표기 문자열을 그대로 들고 다니다가
 * 상세 화면이 그 문자열을 숫자로 되돌려 쓰는 바람에, 표기가 바뀌면 값이 조용히 달라졌다.
 */
public record StockSelection(
        String market,
        String symbol,
        String name,
        String exchange,
        String currency
) {
    public StockSelection {
        market = requireText(market, "market");
        symbol = requireText(symbol, "symbol");
        name = requireText(name, "name");
        exchange = requireText(exchange, "exchange");
        currency = requireText(currency, "currency");
    }

    public static StockSelection samsungElectronics() {
        return new StockSelection("국내", "005930", "삼성전자", "KRX", "KRW");
    }

    public static StockSelection from(SecuritySummary summary) {
        Objects.requireNonNull(summary, "summary");
        return new StockSelection(summary.market(), summary.symbol(), summary.name(),
                summary.exchange(), summary.currency());
    }

    /** 원화가 아닌 종목인지 여부. 금액 표기와 호가 단위를 고르는 데 쓴다. */
    public boolean overseas() {
        return !"KRW".equalsIgnoreCase(currency);
    }

    public SecurityId securityId() {
        return SecurityId.of(symbol, exchange);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
