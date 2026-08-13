package org.ossproject.desktop.viewmodel;

import java.util.Objects;

/** 현재 화면과 주문 화면이 함께 사용하는 선택 종목. */
public record StockSelection(
        String market,
        String symbol,
        String name,
        String exchange,
        String displayPrice,
        String displayChange
) {
    public StockSelection {
        market = requireText(market, "market");
        symbol = requireText(symbol, "symbol");
        name = requireText(name, "name");
        exchange = requireText(exchange, "exchange");
        displayPrice = Objects.requireNonNullElse(displayPrice, "-");
        displayChange = Objects.requireNonNullElse(displayChange, "-");
    }

    public static StockSelection samsungElectronics() {
        return new StockSelection("국내", "005930", "삼성전자", "KRX", "73,500원", "+3.23%");
    }

    public boolean overseas() {
        return "미국".equals(market) || !"KRX".equalsIgnoreCase(exchange);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
