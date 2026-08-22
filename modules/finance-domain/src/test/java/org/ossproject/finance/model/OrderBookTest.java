package org.ossproject.finance.model;

import org.ossproject.finance.model.orderbook.OrderBook;
import org.ossproject.finance.model.orderbook.OrderBookLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderBookTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    /** 매도 73,500 부터 위로, 매수 73,400 부터 아래로 100원 간격. */
    private OrderBook sampleBook() {
        List<OrderBookLevel> levels = List.of(
                new OrderBookLevel(1, new BigDecimal("73500"), 180, 20,
                        new BigDecimal("73400"), 310, -40),
                new OrderBookLevel(2, new BigDecimal("73600"), 620, 0,
                        new BigDecimal("73300"), 890, 15),
                new OrderBookLevel(3, new BigDecimal("73700"), 1240, -5,
                        new BigDecimal("73200"), 450, 0));
        return OrderBook.of("005930", levels, NOW);
    }

    @Test
    @DisplayName("최우선 호가와 스프레드를 계산한다")
    void calculatesBestQuotesAndSpread() {
        OrderBook book = sampleBook();

        assertEquals(0, new BigDecimal("73500").compareTo(book.bestAsk().orElseThrow()));
        assertEquals(0, new BigDecimal("73400").compareTo(book.bestBid().orElseThrow()));
        assertEquals(0, new BigDecimal("100").compareTo(book.spread().orElseThrow()));
        assertEquals(0, new BigDecimal("73450.00").compareTo(book.midPrice().orElseThrow()));
    }

    @Test
    @DisplayName("총잔량을 단계별 합계로 계산한다")
    void sumsTotals() {
        OrderBook book = sampleBook();

        assertEquals(180 + 620 + 1240, book.totalAskSize());
        assertEquals(310 + 890 + 450, book.totalBidSize());
        assertEquals(1240, book.maxSize());
    }

    @Test
    @DisplayName("매수 비중을 백분율로 계산한다")
    void calculatesBidRatio() {
        OrderBook book = sampleBook();

        // 매수 1650 / 전체 3690 = 44.7%
        assertEquals(0, new BigDecimal("44.7").compareTo(book.bidRatioPercent()));
    }

    @Test
    @DisplayName("단계는 항상 오름차순으로 정렬된다")
    void sortsLevels() {
        OrderBook book = OrderBook.of("005930", List.of(
                OrderBookLevel.of(3, new BigDecimal("73700"), 10, new BigDecimal("73200"), 10),
                OrderBookLevel.of(1, new BigDecimal("73500"), 10, new BigDecimal("73400"), 10),
                OrderBookLevel.of(2, new BigDecimal("73600"), 10, new BigDecimal("73300"), 10)), NOW);

        assertEquals(List.of(1, 2, 3), book.levels().stream().map(OrderBookLevel::level).toList());
    }

    @Test
    @DisplayName("단계 수를 고정하지 않아 5단계만 와도 동작한다")
    void acceptsPartialDepth() {
        OrderBook book = OrderBook.of("005930", List.of(
                OrderBookLevel.of(1, new BigDecimal("73500"), 10, new BigDecimal("73400"), 10),
                OrderBookLevel.of(2, new BigDecimal("73600"), 10, new BigDecimal("73300"), 10)), NOW);

        assertEquals(2, book.depth());
        assertTrue(book.bestAsk().isPresent());
    }

    @Test
    @DisplayName("한쪽 호가가 비어 있으면 스프레드는 비어 있다")
    void handlesOneSidedBook() {
        OrderBook book = OrderBook.of("005930", List.of(
                new OrderBookLevel(1, new BigDecimal("73500"), 100, 0, null, 0, 0)), NOW);

        assertTrue(book.bestAsk().isPresent());
        assertTrue(book.bestBid().isEmpty());
        assertTrue(book.spread().isEmpty());
        assertEquals(0, new BigDecimal("73500").compareTo(book.midPrice().orElseThrow()));
    }

    @Test
    @DisplayName("빈 호가창도 안전하게 다룬다")
    void handlesEmptyBook() {
        OrderBook book = OrderBook.of("005930", List.of(), NOW);

        assertTrue(book.isEmpty());
        assertEquals(0L, book.maxSize());
        assertEquals(0, BigDecimal.ZERO.compareTo(book.bidRatioPercent()));
        assertTrue(book.describe().contains("없습니다"));
    }

    @Test
    @DisplayName("음성 요약에 최우선 호가와 매수 비중이 들어간다")
    void describesForSpeech() {
        String description = sampleBook().describe();

        assertTrue(description.contains("73400"));
        assertTrue(description.contains("73500"));
        assertTrue(description.contains("매수 비중"));
    }
}
