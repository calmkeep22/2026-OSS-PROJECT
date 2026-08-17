package org.ossproject.finance.model;

import java.util.Locale;

/**
 * 거래소 식별자.
 *
 * <p>종목코드만으로는 KRX와 NXT의 동일 종목을 구분할 수 없으므로 공개 계약에서는
 * 문자열 대신 이 값을 사용한다. 해외주식 UI에서 이미 사용하는 주요 미국 거래소도
 * 함께 포함한다.
 */
public enum Exchange {
    KRX,
    NXT,
    SOR,
    NASDAQ,
    NYSE,
    NYSE_ARCA,
    AMEX;

    public static Exchange fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("거래소는 필수입니다.");
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "KOSPI", "KOSDAQ", "KONEX" -> KRX;
            case "ARCA" -> NYSE_ARCA;
            default -> {
                try {
                    yield Exchange.valueOf(normalized);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("지원하지 않는 거래소입니다: " + code, unknown);
                }
            }
        };
    }
}
