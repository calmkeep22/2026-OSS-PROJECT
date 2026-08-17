package org.ossproject.desktop.state;

import org.ossproject.desktop.viewmodel.StockSelection;
import org.ossproject.finance.model.SecuritySummary;

import java.util.Objects;

/** 관심종목의 영속 식별 정보. 가격과 등락률은 저장하지 않고 조회 포트에서 다시 가져온다. */
public record WatchlistItem(
        String group,
        String market,
        String symbol,
        String securityName,
        String exchange,
        String currency,
        String alertText
) {
    public static final String UNKNOWN_EXCHANGE = "UNKNOWN";

    public WatchlistItem {
        group = required(group, "group");
        market = required(market, "market");
        symbol = required(symbol, "symbol");
        securityName = required(securityName, "securityName");
        exchange = required(exchange, "exchange");
        currency = required(currency, "currency").toUpperCase(java.util.Locale.ROOT);
        alertText = fallback(alertText, "없음");
    }

    public static WatchlistItem from(String group, SecuritySummary summary, String alertText) {
        Objects.requireNonNull(summary, "summary");
        return new WatchlistItem(group, summary.market(), summary.symbol(), summary.name(),
                summary.exchange(), summary.currency(), alertText);
    }

    /** 예전 저장 형식은 최초 조회 때 종목명으로 식별 정보를 복구한다. */
    public static WatchlistItem legacy(String group, String securityName, String displayPrice, String alertText) {
        boolean usd = displayPrice != null && displayPrice.strip().startsWith("$");
        return new WatchlistItem(group, usd ? "미국" : "국내", securityName, securityName,
                UNKNOWN_EXCHANGE, usd ? "USD" : "KRW", alertText);
    }

    public StockSelection toSelection() {
        return new StockSelection(market, symbol, securityName, exchange, currency);
    }

    public boolean needsIdentityRepair() {
        return UNKNOWN_EXCHANGE.equals(exchange);
    }

    public boolean overseas() {
        return !"KRW".equalsIgnoreCase(currency);
    }

    public WatchlistItem withGroup(String replacement) {
        return new WatchlistItem(replacement, market, symbol, securityName, exchange, currency, alertText);
    }

    public WatchlistItem withAlertText(String replacement) {
        return new WatchlistItem(group, market, symbol, securityName, exchange, currency, replacement);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
