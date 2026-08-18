package org.ossproject.finance.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceLadderTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    /** 중심가 기준 위로 매도, 아래로 매수 100원 간격 호가창. */
    private OrderBook bookAround(long center, int depth) {
        List<OrderBookLevel> levels = new ArrayList<>();
        for (int i = 1; i <= depth; i++) {
            levels.add(new OrderBookLevel(i,
                    BigDecimal.valueOf(center + 100L * i), 100L * i, 0,
                    BigDecimal.valueOf(center - 100L * i), 100L * i, 0));
        }
        return OrderBook.of("005930", levels, NOW);
    }

    private PriceLadder freshLadder() {
        return PriceLadder.create(new PriceLadderConfig(11, 2, null, 0.08));
    }

    @Test
    @DisplayName("첫 호가창을 받으면 자리를 잡고 호가 단위를 추론한다")
    void initializesFromFirstBook() {
        PriceLadder.Update update = freshLadder().update(bookAround(73_500, 5), null);

        assertTrue(update.ladder().isInitialized());
        assertEquals(0, new BigDecimal("100").compareTo(update.ladder().tickSize()));
        assertEquals(11, update.view().rows().size());
    }

    @Test
    @DisplayName("가격이 조금 움직여도 격자는 그대로 고정된다")
    void keepsGridStableOnSmallMoves() {
        PriceLadder ladder = freshLadder().update(bookAround(73_500, 5), null).ladder();
        List<BigDecimal> before = ladder.prices();

        PriceLadder.Update update = ladder.update(bookAround(73_600, 5), new BigDecimal("73600"));

        assertEquals(before, update.ladder().prices());
        assertFalse(update.view().recentered());
        assertTrue(update.view().announcementIfPresent().isEmpty());
    }

    @Test
    @DisplayName("가격이 가장자리 여유 안까지 밀리면 축을 다시 잡고 알린다")
    void recentersWhenPriceReachesEdge() {
        PriceLadder ladder = freshLadder().update(bookAround(73_500, 5), null).ladder();
        List<BigDecimal> before = ladder.prices();

        // 11행, 여유 2행 → 중심에서 ±3틱(300원)을 넘으면 재조정
        PriceLadder.Update update = ladder.update(bookAround(74_000, 5), new BigDecimal("74000"));

        assertTrue(update.view().recentered());
        assertNotEquals(before, update.ladder().prices());
        assertTrue(update.view().announcementIfPresent().orElseThrow().contains("옮겼습니다"));
    }

    @Test
    @DisplayName("첫 자리잡기는 재조정 알림을 내지 않는다")
    void doesNotAnnounceOnFirstPlacement() {
        PriceLadder.Update update = freshLadder().update(bookAround(73_500, 5), null);

        assertFalse(update.view().recentered());
        assertTrue(update.view().announcementIfPresent().isEmpty());
    }

    @Test
    @DisplayName("잔량을 최대값 기준으로 정규화한다")
    void normalizesBars() {
        PriceLadder.Update update = freshLadder().update(bookAround(73_500, 5), null);

        // 최대 잔량은 5단계의 500주
        assertEquals(500L, update.view().maxSize());

        PriceLadderRow top = update.view().rows().stream()
                .filter(row -> row.askSize() == 500L).findFirst().orElseThrow();
        assertEquals(1.0, top.askBarRatio(), 0.0001);

        PriceLadderRow small = update.view().rows().stream()
                .filter(row -> row.askSize() == 100L).findFirst().orElseThrow();
        assertEquals(0.2, small.askBarRatio(), 0.0001);
    }

    @Test
    @DisplayName("잔량이 있으면 최소 막대 길이를 보장한다")
    void guaranteesMinimumBar() {
        PriceLadder ladder = PriceLadder.create(
                new PriceLadderConfig(11, 2, new BigDecimal("100"), 0.30));
        OrderBook book = OrderBook.of("005930", List.of(
                new OrderBookLevel(1, new BigDecimal("73500"), 1, 0,
                        new BigDecimal("73400"), 10_000, 0)), NOW);

        PriceLadder.Update update = ladder.update(book, null);

        // 1주는 10,000주 대비 0.01% 지만, 최소 비율 30% 로 끌어올려 눈에 보이게 만든다.
        PriceLadderRow tiny = update.view().rows().stream()
                .filter(row -> row.askSize() == 1L).findFirst().orElseThrow();
        assertEquals(0.30, tiny.askBarRatio(), 0.0001);
    }

    @Test
    @DisplayName("단계가 하나뿐이고 호가 단위 설정도 없으면 격자를 잡지 않는다")
    void doesNotGuessTickFromSingleLevel() {
        // 가격 간격을 관측할 수 없으므로 추론이 불가능하다. 잘못된 격자를 그리느니
        // 아무것도 그리지 않는 편이 안전하다.
        OrderBook book = OrderBook.of("005930", List.of(
                new OrderBookLevel(1, new BigDecimal("73500"), 1, 0,
                        new BigDecimal("73400"), 10_000, 0)), NOW);

        PriceLadder.Update update = freshLadder().update(book, null);

        assertFalse(update.ladder().isInitialized());
        assertTrue(update.view().rows().isEmpty());
    }

    @Test
    @DisplayName("잔량이 0이면 막대를 그리지 않는다")
    void drawsNothingForEmptyLevels() {
        PriceLadder.Update update = freshLadder().update(bookAround(73_500, 2), null);

        List<PriceLadderRow> empty = update.view().rows().stream()
                .filter(PriceLadderRow::isEmpty).toList();

        assertFalse(empty.isEmpty());
        assertTrue(empty.stream().allMatch(row -> row.askBarRatio() == 0.0 && row.bidBarRatio() == 0.0));
    }

    @Test
    @DisplayName("행은 높은 가격부터 낮은 가격 순이다")
    void ordersRowsHighToLow() {
        PriceLadder.Update update = freshLadder().update(bookAround(73_500, 5), null);
        List<PriceLadderRow> rows = update.view().rows();

        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).price().compareTo(rows.get(i).price()) > 0);
        }
    }

    @Test
    @DisplayName("기준가 행을 표시한다")
    void marksCurrentPriceRow() {
        PriceLadder.Update update = freshLadder().update(bookAround(73_500, 5), new BigDecimal("73500"));

        PriceLadderRow current = update.view().currentPriceRow().orElseThrow();
        assertEquals(0, new BigDecimal("73500").compareTo(current.price()));
        assertTrue(current.describe().contains("현재가"));
    }

    @Test
    @DisplayName("행 설명에 매도·매수와 증감을 말로 담는다")
    void describesRowForSpeech() {
        PriceLadderRow row = new PriceLadderRow(new BigDecimal("73500"),
                180, 0, 20, 0, 0.5, 0.0, false);

        String description = row.describe();

        assertTrue(description.contains("73500원"));
        assertTrue(description.contains("매도 180주"));
        assertTrue(description.contains("20주 증가"));
    }

    @Test
    @DisplayName("잔량 감소도 말로 알린다")
    void describesDecrease() {
        PriceLadderRow row = new PriceLadderRow(new BigDecimal("73400"),
                0, 310, 0, -40, 0.0, 0.5, false);

        assertTrue(row.describe().contains("40주 감소"));
    }

    @Test
    @DisplayName("호가 단위를 설정으로 고정할 수 있다")
    void honoursConfiguredTickSize() {
        PriceLadder ladder = PriceLadder.create(
                new PriceLadderConfig(11, 2, new BigDecimal("50"), 0.08));

        PriceLadder.Update update = ladder.update(bookAround(73_500, 5), null);

        assertEquals(0, new BigDecimal("50").compareTo(update.ladder().tickSize()));
        // 50원 간격이면 11행이 73,250 ~ 73,750
        assertEquals(0, new BigDecimal("73750").compareTo(update.view().highestPrice().orElseThrow()));
        assertEquals(0, new BigDecimal("73250").compareTo(update.view().lowestPrice().orElseThrow()));
    }

    @Test
    @DisplayName("호가창이 비어 있으면 빈 화면을 돌려주고 격자를 망가뜨리지 않는다")
    void handlesEmptyBook() {
        PriceLadder ladder = freshLadder();

        PriceLadder.Update update = ladder.update(OrderBook.of("005930", List.of(), NOW), null);

        assertTrue(update.view().rows().isEmpty());
        assertFalse(update.ladder().isInitialized());
    }

    @Test
    @DisplayName("자리를 잡은 뒤 빈 호가창이 와도 격자를 유지한다")
    void keepsGridWhenBookGoesEmpty() {
        PriceLadder ladder = freshLadder().update(bookAround(73_500, 5), null).ladder();

        PriceLadder.Update update = ladder.update(OrderBook.of("005930", List.of(), NOW),
                new BigDecimal("73500"));

        assertTrue(update.ladder().isInitialized());
        assertEquals(ladder.prices(), update.ladder().prices());
    }
}
