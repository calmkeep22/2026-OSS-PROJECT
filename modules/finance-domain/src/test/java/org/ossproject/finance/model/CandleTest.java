package org.ossproject.finance.model;

import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.PricePoint;
import org.ossproject.finance.model.market.Quote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CandleTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("일봉 PricePoint 와 서로 변환된다")
    void convertsWithPricePoint() {
        PricePoint point = new PricePoint(LocalDate.of(2026, 8, 7),
                new BigDecimal("70000"), new BigDecimal("72000"),
                new BigDecimal("69000"), new BigDecimal("71500"), 8_120_000L);

        Candle candle = Candle.fromDaily(point, SEOUL);

        assertEquals(CandleInterval.DAY, candle.interval());
        assertEquals(point, candle.toPricePoint(SEOUL));
    }

    @Test
    @DisplayName("진행 중인 봉에 새 체결을 반영하면 고가·저가·종가가 갱신된다")
    void mergesTick() {
        Candle candle = new Candle(Instant.parse("2026-08-07T00:30:00Z"), CandleInterval.MINUTE_5,
                new BigDecimal("70000"), new BigDecimal("70500"),
                new BigDecimal("69800"), new BigDecimal("70200"), 1_000L);

        Candle merged = candle.merge(new BigDecimal("71000"), 500L);

        assertEquals(0, new BigDecimal("71000").compareTo(merged.high()));
        assertEquals(0, new BigDecimal("69800").compareTo(merged.low()));
        assertEquals(0, new BigDecimal("71000").compareTo(merged.close()));
        assertEquals(1_500L, merged.volume());
    }

    @Test
    @DisplayName("시가 대비 등락률과 방향을 계산한다")
    void calculatesChange() {
        Candle candle = new Candle(Instant.parse("2026-08-07T00:00:00Z"), CandleInterval.DAY,
                new BigDecimal("70000"), new BigDecimal("72000"),
                new BigDecimal("69000"), new BigDecimal("71400"), 1_000L);

        assertEquals(0, new BigDecimal("2.00").compareTo(candle.changeRate()));
        assertEquals(PriceDirection.UP, candle.direction());
    }

    @Test
    @DisplayName("저가가 고가보다 높으면 거부한다")
    void rejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class, () -> new Candle(
                Instant.parse("2026-08-07T00:00:00Z"), CandleInterval.DAY,
                new BigDecimal("70000"), new BigDecimal("69000"),
                new BigDecimal("71000"), new BigDecimal("70000"), 1_000L));
    }

    @Test
    @DisplayName("전일 종가를 알면 등락률을 계산하고 모르면 0이다")
    void calculatesQuoteChange() {
        Quote withPrevious = new Quote("005930", new BigDecimal("73500"), new BigDecimal("70000"),
                null, null, 0L, 0L, 1_000L, Instant.parse("2026-08-08T01:00:00Z"));
        Quote withoutPrevious = Quote.of("005930", new BigDecimal("73500"), 1_000L,
                Instant.parse("2026-08-08T01:00:00Z"));

        assertEquals(0, new BigDecimal("5.00").compareTo(withPrevious.changeRate()));
        assertEquals(PriceDirection.UP, withPrevious.direction());
        assertEquals(0, BigDecimal.ZERO.compareTo(withoutPrevious.changeRate()));
        assertEquals(PriceDirection.FLAT, withoutPrevious.direction());
    }
}
