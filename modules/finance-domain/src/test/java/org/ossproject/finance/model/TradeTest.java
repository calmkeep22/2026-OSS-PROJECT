package org.ossproject.finance.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TradeTest {

    private static Trade at(String isoUtc) {
        return new Trade("005930", new BigDecimal("73500"), 20L, OrderSide.BUY, Instant.parse(isoUtc));
    }

    /** 시각은 한국 시장 기준으로 읽어야 한다. */
    @Test void showsTheTimeInSeoul() {
        assertEquals("14:32:15", at("2026-08-20T05:32:15Z").timeText());
    }

    /** 매수와 매도를 색으로만 구분하면 전달되지 않는 사용자가 있다. */
    @Test void spellsOutTheSideInsteadOfRelyingOnColour() {
        String said = at("2026-08-20T05:32:15Z").describe();

        assertTrue(said.contains("14:32:15"), said);
        assertTrue(said.contains("73,500원"), said);
        assertTrue(said.contains("20주"), said);
        assertTrue(said.contains(OrderSide.BUY.displayName()), said);
    }

    @Test void rejectsAZeroQuantityBecauseItIsNotATrade() {
        assertThrows(IllegalArgumentException.class, () -> new Trade("005930",
                new BigDecimal("73500"), 0L, OrderSide.BUY, Instant.EPOCH));
    }

    @Test void rejectsAMissingSide() {
        assertThrows(IllegalArgumentException.class, () -> new Trade("005930",
                new BigDecimal("73500"), 10L, null, Instant.EPOCH));
    }
}
