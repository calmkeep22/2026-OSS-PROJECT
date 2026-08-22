package org.ossproject.ai.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 장중 오늘 봉은 종가가 아니라 현재가다. 그대로 넣으면 "내일을 맞힌다" 가 "이미 본 값을
 * 되읽는다" 가 된다.
 */
class TradingSessionBarsTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 21);

    private static Candle dayBar(LocalDate date) {
        return new Candle(date.atTime(9, 0).atZone(SEOUL).toInstant(), CandleInterval.DAY,
                new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("90"), new BigDecimal("105"), 1000L);
    }

    private static TradingSessionBars at(LocalTime time) {
        return new TradingSessionBars(
                Clock.fixed(TODAY.atTime(time).atZone(SEOUL).toInstant(), SEOUL));
    }

    private static List<Candle> threeDaysEndingToday() {
        return List.of(dayBar(TODAY.minusDays(2)), dayBar(TODAY.minusDays(1)), dayBar(TODAY));
    }

    @Test
    @DisplayName("장중에는 오늘 봉을 뺀다")
    void dropsTodaysBarWhileTheMarketIsOpen() {
        List<Candle> settled = at(LocalTime.of(11, 0)).settled(threeDaysEndingToday());

        assertEquals(2, settled.size());
        assertEquals(dayBar(TODAY.minusDays(1)).timestamp(), settled.get(1).timestamp());
    }

    /** 마감 직후에도 체결과 정정이 남아 있다. 20분을 기다린다. */
    @Test
    @DisplayName("마감 직후에도 아직 확정으로 보지 않는다")
    void stillDropsRightAfterTheBell() {
        assertEquals(2, at(LocalTime.of(15, 35)).settled(threeDaysEndingToday()).size());
    }

    @Test
    @DisplayName("마감 20분 뒤부터는 오늘 봉을 그대로 쓴다")
    void keepsTodaysBarOnceItIsSettled() {
        assertEquals(3, at(LocalTime.of(15, 50)).settled(threeDaysEndingToday()).size());
        assertEquals(3, at(LocalTime.of(18, 0)).settled(threeDaysEndingToday()).size());
    }

    /** 어제까지의 봉만 있으면 뺄 것이 없다. 장중이라도 그대로 쓴다. */
    @Test
    @DisplayName("마지막 봉이 오늘 것이 아니면 그대로 둔다")
    void leavesOlderBarsAloneEvenDuringTheSession() {
        List<Candle> yesterdayEnd = List.of(dayBar(TODAY.minusDays(2)), dayBar(TODAY.minusDays(1)));

        assertEquals(2, at(LocalTime.of(11, 0)).settled(yesterdayEnd).size());
    }

    @Test
    @DisplayName("빈 목록과 null 을 견딘다")
    void toleratesEmptyInput() {
        assertTrue(at(LocalTime.of(11, 0)).settled(List.of()).isEmpty());
        assertTrue(at(LocalTime.of(11, 0)).settled(null).isEmpty());
    }

    /** 장중에 오늘 봉 하나만 있으면 넣을 것이 없다. 지어내지 않는다. */
    @Test
    @DisplayName("장중에 오늘 봉 하나뿐이면 빈 목록이 된다")
    void returnsNothingWhenOnlyTodaysBarExistsDuringTheSession() {
        assertTrue(at(LocalTime.of(11, 0)).settled(List.of(dayBar(TODAY))).isEmpty());
    }
}
