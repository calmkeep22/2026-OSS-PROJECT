package org.ossproject.finance.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityIdTest {
    @Test
    void normalizesSymbolAndExchangeCode() {
        SecurityId id = SecurityId.of(" 005930 ", "krx");

        assertEquals("005930", id.symbol());
        assertEquals(Exchange.KRX, id.exchange());
        assertEquals(Exchange.KRX, Exchange.fromCode("KOSDAQ"));
    }

    @Test
    void rejectsMissingOrUnknownIdentityParts() {
        assertThrows(IllegalArgumentException.class, () -> SecurityId.of(" ", "KRX"));
        assertThrows(IllegalArgumentException.class, () -> SecurityId.of("005930", "UNKNOWN"));
    }
}
