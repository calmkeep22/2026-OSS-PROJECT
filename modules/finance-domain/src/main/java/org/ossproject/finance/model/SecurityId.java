package org.ossproject.finance.model;

import java.util.Locale;
import java.util.Objects;

/** 종목코드와 거래소를 함께 보존하는 공개 종목 식별자. */
public record SecurityId(String symbol, Exchange exchange) {
    public SecurityId {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        symbol = symbol.strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(exchange, "exchange");
    }

    public static SecurityId of(String symbol, String exchangeCode) {
        return new SecurityId(symbol, Exchange.fromCode(exchangeCode));
    }
}
