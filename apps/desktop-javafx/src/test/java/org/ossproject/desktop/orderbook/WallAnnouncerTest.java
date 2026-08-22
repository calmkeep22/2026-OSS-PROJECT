package org.ossproject.desktop.orderbook;

import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.orderbook.OrderBook;
import org.ossproject.finance.model.orderbook.OrderBookLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WallAnnouncerTest {

    private final WallAnnouncer announcer = new WallAnnouncer();

    /** 앞선 단계 평균의 세 배 이상이면 벽이다. 마지막 단계에 큰 물량을 둔다. */
    private static OrderBook withAskWall(long wallSize) {
        return OrderBook.of("005930", List.of(
                OrderBookLevel.of(1, new BigDecimal("70100"), 100L, new BigDecimal("70000"), 100L),
                OrderBookLevel.of(2, new BigDecimal("70200"), 100L, new BigDecimal("69900"), 100L),
                OrderBookLevel.of(3, new BigDecimal("70300"), wallSize, new BigDecimal("69800"), 100L)
        ), Instant.parse("2026-08-20T05:00:00Z"));
    }

    @Test void namesTheSideThePriceAndTheSize() {
        Optional<String> said = announcer.onOrderBook(withAskWall(5000L));

        assertTrue(said.isPresent(), "벽이 있으면 알려야 합니다");
        assertTrue(said.get().contains("매도"), said.get());
        assertTrue(said.get().contains("70,300원"), said.get());
        assertTrue(said.get().contains("5,000주"), said.get());
    }

    /** 갱신마다 읽으면 소음이 된다. 같은 자리에 벽이 계속 있으면 다시 말하지 않는다. */
    @Test void staysQuietWhileTheSameWallRemains() {
        announcer.onOrderBook(withAskWall(5000L));

        assertTrue(announcer.onOrderBook(withAskWall(5200L)).isEmpty(),
                "잔량이 조금 변해도 같은 자리면 다시 말하지 않습니다");
    }

    /** 사라진 것도 알려야 판단이 바뀐다. */
    @Test void speaksUpWhenTheWallDisappears() {
        announcer.onOrderBook(withAskWall(5000L));

        Optional<String> said = announcer.onOrderBook(withAskWall(100L));

        assertTrue(said.isPresent());
        assertTrue(said.get().contains("사라졌"), said.get());
    }

    @Test void saysNothingWhenThereWasNeverAWall() {
        assertTrue(announcer.onOrderBook(withAskWall(100L)).isEmpty());
    }

    @Test void forgetsEverythingOnReset() {
        announcer.onOrderBook(withAskWall(5000L));
        announcer.reset();

        assertTrue(announcer.onOrderBook(withAskWall(5000L)).isPresent(),
                "종목이 바뀌면 다시 알려야 합니다");
    }

    @Test void handlesAnEmptyOrderBookWithoutFailing() {
        assertDoesNotThrow(() -> announcer.onOrderBook(
                OrderBook.of("005930", List.of(), Instant.EPOCH)));
    }
}
