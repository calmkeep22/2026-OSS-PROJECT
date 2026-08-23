package org.ossproject.finance.model.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 한글로 쳐도 영문 등록명을 찾는지.
 *
 * <p>실제로 났던 고장이다. 검색창에 "네이버" 를 쳐도, 음성으로 "네이버" 라고 말해도
 * 아무것도 나오지 않았다. 거래소 등록명이 {@code NAVER} 라 글자가 하나도 겹치지
 * 않았기 때문이고, 인식기나 검색 로직의 문제가 아니었다.
 *
 * <p>여기서 맞추므로 검색창과 음성이 같은 규칙을 쓴다. 한쪽만 고치면 반드시 갈라진다.
 */
class SecuritySummaryKoreanSearchTest {

    private static SecuritySummary security(String symbol, String name) {
        return new SecuritySummary(symbol, name, "국내", "KRX", "KRW",
                new java.math.BigDecimal("70000"), new java.math.BigDecimal("1.2"),
                org.ossproject.finance.model.PriceDirection.UP);
    }

    @Test
    @DisplayName("한글로 읽은 대로 쳐도 영문 등록명을 찾는다")
    void findsLatinNamesByTheirKoreanReading() {
        assertTrue(security("035420", "NAVER").matches("네이버"));
        assertTrue(security("051910", "LG화학").matches("엘지화학"));
        assertTrue(security("105560", "KB금융").matches("케이비금융"));
    }

    @Test
    @DisplayName("원래 방식도 그대로 된다")
    void keepsMatchingTheRegisteredForm() {
        assertTrue(security("035420", "NAVER").matches("NAVER"));
        assertTrue(security("035420", "NAVER").matches("naver"));
        assertTrue(security("035420", "NAVER").matches("035420"));
        assertTrue(security("005930", "삼성전자").matches("삼성"));
    }

    @Test
    @DisplayName("상관없는 말은 여전히 안 걸린다")
    void doesNotMatchUnrelatedWords() {
        assertFalse(security("035420", "NAVER").matches("카카오"));
        assertFalse(security("005930", "삼성전자").matches("네이버"));
    }
}
