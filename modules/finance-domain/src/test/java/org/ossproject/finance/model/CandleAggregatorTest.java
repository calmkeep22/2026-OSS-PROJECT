package org.ossproject.finance.model;

import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleAggregator;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.Quote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleAggregatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-08-20 09:30:00 KST */
    private static final Instant OPEN = Instant.parse("2026-08-20T00:30:00Z");

    private Quote tick(String price, long cumulativeVolume, long secondsAfterOpen) {
        return Quote.of("005930", new BigDecimal(price), cumulativeVolume,
                OPEN.plusSeconds(secondsAfterOpen));
    }

    private CandleAggregator minuteAggregator() {
        return new CandleAggregator(CandleInterval.MINUTE_1, SEOUL);
    }

    @Test
    @DisplayName("첫 체결이 봉을 시작하고 네 값이 모두 그 가격이 된다")
    void startsCandleOnFirstTrade() {
        CandleAggregator aggregator = minuteAggregator();

        Candle candle = aggregator.onQuote(tick("73500", 1_000, 0)).orElseThrow().candle();

        assertEquals(0, new BigDecimal("73500").compareTo(candle.open()));
        assertEquals(0, new BigDecimal("73500").compareTo(candle.high()));
        assertEquals(0, new BigDecimal("73500").compareTo(candle.low()));
        assertEquals(0, new BigDecimal("73500").compareTo(candle.close()));
    }

    @Test
    @DisplayName("첫 체결은 거래량 기준이 없어 0으로 둔다")
    void firstTradeHasNoVolumeBaseline() {
        CandleAggregator aggregator = minuteAggregator();

        Candle candle = aggregator.onQuote(tick("73500", 1_000_000, 0)).orElseThrow().candle();

        assertEquals(0L, candle.volume(), "누적값을 그대로 쓰면 봉 거래량이 폭발한다");
    }

    @Test
    @DisplayName("거래량은 누적값의 차이로 계산한다")
    void usesCumulativeVolumeDelta() {
        CandleAggregator aggregator = minuteAggregator();
        aggregator.onQuote(tick("73500", 1_000, 0));

        aggregator.onQuote(tick("73600", 1_300, 10));
        Candle candle = aggregator.onQuote(tick("73400", 1_500, 20)).orElseThrow().candle();

        // (1300-1000) + (1500-1300) = 500
        assertEquals(500L, candle.volume());
    }

    @Test
    @DisplayName("같은 분 안의 체결은 고가·저가·종가를 갱신한다")
    void mergesWithinSameMinute() {
        CandleAggregator aggregator = minuteAggregator();
        aggregator.onQuote(tick("73500", 1_000, 0));
        aggregator.onQuote(tick("74000", 1_100, 10));
        aggregator.onQuote(tick("73000", 1_200, 20));

        Candle candle = aggregator.onQuote(tick("73800", 1_300, 30)).orElseThrow().candle();

        assertEquals(0, new BigDecimal("73500").compareTo(candle.open()), "시가는 첫 체결가 그대로");
        assertEquals(0, new BigDecimal("74000").compareTo(candle.high()));
        assertEquals(0, new BigDecimal("73000").compareTo(candle.low()));
        assertEquals(0, new BigDecimal("73800").compareTo(candle.close()), "종가는 마지막 체결가");
    }

    @Test
    @DisplayName("분이 바뀌면 새 봉을 시작하고 직전 봉을 마감해 돌려준다")
    void rollsOverToNewCandle() {
        CandleAggregator aggregator = minuteAggregator();
        aggregator.onQuote(tick("73500", 1_000, 0));
        aggregator.onQuote(tick("74000", 1_200, 30));

        CandleAggregator.Result result = aggregator.onQuote(tick("73900", 1_400, 65)).orElseThrow();

        assertTrue(result.startedNewCandle());
        Candle completed = result.completed().orElseThrow();
        assertEquals(0, new BigDecimal("73500").compareTo(completed.open()));
        assertEquals(0, new BigDecimal("74000").compareTo(completed.close()));

        Candle started = result.candle();
        assertEquals(0, new BigDecimal("73900").compareTo(started.open()));
        assertEquals(200L, started.volume(), "새 봉의 거래량도 차이로 계산");
    }

    @Test
    @DisplayName("봉 시작 시각을 분 경계에 맞춘다")
    void alignsToMinuteBoundary() {
        CandleAggregator aggregator = minuteAggregator();

        // 09:30:37 에 온 체결도 봉 시각은 09:30:00
        Candle candle = aggregator.onQuote(tick("73500", 1_000, 37)).orElseThrow().candle();

        assertEquals(OPEN, candle.timestamp());
    }

    @Test
    @DisplayName("5분봉은 5분 경계에 맞춘다")
    void alignsToFiveMinuteBoundary() {
        CandleAggregator aggregator = new CandleAggregator(CandleInterval.MINUTE_5, SEOUL);

        // 09:32 와 09:34 는 같은 09:30 봉
        Candle first = aggregator.onQuote(tick("73500", 1_000, 120)).orElseThrow().candle();
        CandleAggregator.Result second = aggregator.onQuote(tick("73800", 1_100, 240)).orElseThrow();

        assertEquals(OPEN, first.timestamp());
        assertFalse(second.startedNewCandle());

        // 09:36 은 09:35 봉으로 넘어간다
        CandleAggregator.Result third = aggregator.onQuote(tick("73900", 1_200, 360)).orElseThrow();
        assertTrue(third.startedNewCandle());
    }

    @Test
    @DisplayName("일봉은 하루 동안 하나로 유지된다")
    void keepsSingleDailyCandle() {
        CandleAggregator aggregator = new CandleAggregator(CandleInterval.DAY, SEOUL);
        aggregator.onQuote(tick("73500", 1_000, 0));

        // 6시간 뒤에도 같은 날
        CandleAggregator.Result result = aggregator.onQuote(tick("74500", 5_000, 6 * 3600)).orElseThrow();

        assertFalse(result.startedNewCandle());
        assertEquals(0, new BigDecimal("74500").compareTo(result.candle().high()));
    }

    @Test
    @DisplayName("누적 거래량이 줄면 장이 새로 시작된 것으로 본다")
    void handlesVolumeResetOnNewSession() {
        CandleAggregator aggregator = minuteAggregator();
        aggregator.onQuote(tick("73500", 900_000, 0));

        // 다음 날 첫 체결: 누적이 초기화되어 줄어든다
        Candle candle = aggregator.onQuote(tick("74000", 500, 86_400)).orElseThrow().candle();

        assertEquals(500L, candle.volume(), "음수 거래량이 나오면 안 된다");
    }

    @Test
    @DisplayName("종목별로 따로 쌓는다")
    void tracksSymbolsIndependently() {
        CandleAggregator aggregator = minuteAggregator();
        aggregator.onQuote(tick("73500", 1_000, 0));
        aggregator.onQuote(Quote.of("000660", new BigDecimal("190000"), 500, OPEN.plusSeconds(5)));

        assertEquals(0, new BigDecimal("73500")
                .compareTo(aggregator.current("005930").orElseThrow().close()));
        assertEquals(0, new BigDecimal("190000")
                .compareTo(aggregator.current("000660").orElseThrow().close()));
    }

    @Test
    @DisplayName("과거 조회의 마지막 봉을 이어받는다")
    void primesFromHistory() {
        CandleAggregator aggregator = minuteAggregator();
        Candle fromRest = new Candle(OPEN, CandleInterval.MINUTE_1,
                new BigDecimal("73000"), new BigDecimal("73200"),
                new BigDecimal("72900"), new BigDecimal("73100"), 5_000);

        aggregator.prime("005930", fromRest, OPEN.plusSeconds(20));
        Candle candle = aggregator.onQuote(tick("73500", 100, 30)).orElseThrow().candle();

        assertEquals(0, new BigDecimal("73000").compareTo(candle.open()), "과거 봉의 시가를 유지");
        assertEquals(0, new BigDecimal("73500").compareTo(candle.high()), "고가는 갱신");
        assertEquals(5_000L, candle.volume(), "첫 체결은 기준이 없어 거래량을 더하지 않는다");
    }

    @Test
    @DisplayName("이미 지난 시간대의 봉은 이어받지 않는다")
    void ignoresStalePrime() {
        CandleAggregator aggregator = minuteAggregator();
        Candle yesterday = new Candle(OPEN.minusSeconds(86_400), CandleInterval.MINUTE_1,
                new BigDecimal("70000"), new BigDecimal("70000"),
                new BigDecimal("70000"), new BigDecimal("70000"), 1_000);

        aggregator.prime("005930", yesterday, OPEN);
        Candle candle = aggregator.onQuote(tick("73500", 100, 0)).orElseThrow().candle();

        assertEquals(0, new BigDecimal("73500").compareTo(candle.open()), "새 봉으로 시작");
        assertEquals(OPEN, candle.timestamp());
    }

    @Test
    @DisplayName("종목을 잊으면 다음 체결이 새 봉을 시작한다")
    void forgetsSymbol() {
        CandleAggregator aggregator = minuteAggregator();
        aggregator.onQuote(tick("73500", 1_000, 0));

        aggregator.forget("005930");

        assertTrue(aggregator.current("005930").isEmpty());
        Candle candle = aggregator.onQuote(tick("74000", 2_000, 10)).orElseThrow().candle();
        assertEquals(0, new BigDecimal("74000").compareTo(candle.open()));
    }

    @Test
    @DisplayName("null 시세는 무시한다")
    void ignoresNullQuote() {
        assertTrue(minuteAggregator().onQuote(null).isEmpty());
    }
}
