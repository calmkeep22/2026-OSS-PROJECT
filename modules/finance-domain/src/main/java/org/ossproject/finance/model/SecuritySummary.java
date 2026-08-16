package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 검색 결과 한 건. 상세를 조회하기 전에 목록에 보여 주는 최소 정보다.
 *
 * <p>거래소와 통화를 값으로 들고 있는 이유는 화면이 종목 코드 모양만 보고 시장을 추측하지
 * 않게 하려는 것이다. 같은 종목 코드가 KRX와 NXT에 함께 있을 수 있고, 통화를 모르면
 * 금액 표기를 화면이 임의로 정하게 된다.
 *
 * <p>가격은 목록 표시용이며, 정확한 시가·고가·저가·거래량은 {@link StockDetail} 로 따로
 * 조회한다.
 */
public record SecuritySummary(
        String symbol,
        String name,
        String market,
        String exchange,
        String currency,
        BigDecimal currentPrice,
        BigDecimal changeRate,
        PriceDirection direction
) {
    public SecuritySummary {
        symbol = requireText(symbol, "종목 코드");
        name = requireText(name, "종목명");
        market = requireText(market, "시장 구분");
        exchange = requireText(exchange, "거래소");
        currency = requireText(currency, "통화");
        if (currentPrice == null || currentPrice.signum() <= 0) {
            throw new IllegalArgumentException("현재가는 0보다 커야 합니다.");
        }
        if (changeRate == null) {
            throw new IllegalArgumentException("등락률은 필수입니다.");
        }
        Objects.requireNonNull(direction, "direction");
    }

    /** 원화 종목인지 여부. 화면의 금액 표기를 고르는 데 쓴다. */
    public boolean isKrw() {
        return "KRW".equalsIgnoreCase(currency);
    }

    /** 검색어가 종목 코드나 종목명에 걸리는지 확인한다. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.strip().toLowerCase(java.util.Locale.ROOT);
        return symbol.toLowerCase(java.util.Locale.ROOT).contains(normalized)
                || name.toLowerCase(java.util.Locale.ROOT).contains(normalized);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "은(는) 필수입니다.");
        }
        return value.trim();
    }
}
