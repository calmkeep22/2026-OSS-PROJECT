package org.ossproject.desktop.viewmodel;

/** 검색 화면에서 표시하는 정규화된 종목 한 건. */
public record StockSearchItem(
        String market,
        String symbol,
        String name,
        String exchange,
        String price,
        String changeRate
) {
    public StockSelection toSelection() {
        return new StockSelection(market, symbol, name, exchange, price, changeRate);
    }

    public boolean matches(String query, String marketFilter) {
        String normalized = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        boolean marketMatches = marketFilter == null || marketFilter.equals("전체") || market.equals(marketFilter);
        boolean textMatches = normalized.isBlank()
                || symbol.toLowerCase(java.util.Locale.ROOT).contains(normalized)
                || name.toLowerCase(java.util.Locale.ROOT).contains(normalized);
        return marketMatches && textMatches;
    }
}
