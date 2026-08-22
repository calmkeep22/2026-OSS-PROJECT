package org.ossproject.broker;

import org.ossproject.broker.auth.SensitiveDataMasker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveDataMaskerTest {

    @Test
    @DisplayName("JSON 본문의 키와 시크릿을 가린다")
    void masksJsonSecrets() {
        String body = "{\"appkey\":\"PS1a2b3c4d5e\",\"appsecret\":\"S9z8y7x6w5\",\"grant_type\":\"client_credentials\"}";

        String masked = SensitiveDataMasker.mask(body);

        assertFalse(masked.contains("PS1a2b3c4d5e"));
        assertFalse(masked.contains("S9z8y7x6w5"));
        assertTrue(masked.contains("\"appkey\":\"****\""));
        assertTrue(masked.contains("\"grant_type\":\"client_credentials\""));
    }

    @Test
    @DisplayName("액세스 토큰 필드를 가린다")
    void masksAccessToken() {
        String body = "{\"access_token\":\"eyJhbGciOiJIUzI1NiJ9.payload.sig\",\"expires_in\":86400}";

        String masked = SensitiveDataMasker.mask(body);

        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(masked.contains("\"access_token\":\"****\""));
    }

    @Test
    @DisplayName("Bearer 헤더의 토큰을 가린다")
    void masksBearerHeader() {
        String header = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig";

        String masked = SensitiveDataMasker.mask(header);

        assertEquals("Authorization: Bearer ****", masked);
    }

    @Test
    @DisplayName("8자리 이상 숫자는 계좌번호로 보고 뒤 4자리만 남긴다")
    void masksLongDigits() {
        String log = "계좌 12345678901 조회 요청";

        assertEquals("계좌 *******8901 조회 요청", SensitiveDataMasker.mask(log));
    }

    @Test
    @DisplayName("짧은 숫자는 건드리지 않는다")
    void keepsShortNumbers() {
        String log = "종목 005930 수량 10주 가격 73500";

        assertEquals(log, SensitiveDataMasker.mask(log));
    }

    @Test
    @DisplayName("계좌번호는 뒤 4자리만 남긴다")
    void masksAccountNo() {
        assertEquals("*******8901", SensitiveDataMasker.maskAccountNo("12345678901"));
        assertEquals("*******8901", SensitiveDataMasker.maskAccountNo("123-456-78901"));
        assertEquals("****", SensitiveDataMasker.maskAccountNo("12"));
        assertEquals("****", SensitiveDataMasker.maskAccountNo(null));
    }

    @Test
    @DisplayName("짧은 시크릿은 통째로 가린다")
    void masksShortSecretEntirely() {
        assertEquals("****", SensitiveDataMasker.maskSecret("abc123"));
        assertEquals("PS1a****", SensitiveDataMasker.maskSecret("PS1a2b3c4d5e"));
    }

    @Test
    @DisplayName("null 과 빈 문자열은 그대로 돌려준다")
    void handlesNullAndEmpty() {
        assertEquals(null, SensitiveDataMasker.mask(null));
        assertEquals("", SensitiveDataMasker.mask(""));
    }
}
