package org.ossproject.finance.model;

import org.ossproject.finance.model.orderbook.DepthChart;
import org.ossproject.finance.model.orderbook.DepthChartConfig;
import org.ossproject.finance.model.orderbook.DepthChartView;
import org.ossproject.finance.model.orderbook.DepthCurve;
import org.ossproject.finance.model.orderbook.DepthPoint;
import org.ossproject.finance.model.orderbook.OrderBook;
import org.ossproject.finance.model.orderbook.OrderBookLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepthChartTest {

    private static final Instant NOW = Instant.parse("2026-08-19T01:00:00Z");

    /** 중심가 기준 100원 간격, 단계마다 잔량 {@code sizes[i]}. */
    private OrderBook book(long center, long... sizes) {
        List<OrderBookLevel> levels = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            int level = i + 1;
            levels.add(OrderBookLevel.of(level,
                    BigDecimal.valueOf(center + 100L * level), sizes[i],
                    BigDecimal.valueOf(center - 100L * level), sizes[i]));
        }
        return OrderBook.of("005930", levels, NOW);
    }

    // ------------------------------------------------------------------
    // 누적 곡선
    // ------------------------------------------------------------------

    @Test
    @DisplayName("최우선 호가부터 차례로 누적한다")
    void accumulatesFromBest() {
        DepthCurve curve = DepthCurve.from(book(73_500, 100, 200, 300));

        assertEquals(List.of(100L, 300L, 600L),
                curve.askSide().stream().map(DepthPoint::cumulativeSize).toList());
        assertEquals(600L, curve.totalAskDepth());
        assertEquals(600L, curve.totalBidDepth());
    }

    @Test
    @DisplayName("매도는 싼 값부터, 매수는 비싼 값부터 쌓인다")
    void accumulatesOutwardFromMid() {
        DepthCurve curve = DepthCurve.from(book(73_500, 100, 200, 300));

        assertEquals(0, new BigDecimal("73600").compareTo(curve.askSide().get(0).price()));
        assertEquals(0, new BigDecimal("73800").compareTo(curve.askSide().get(2).price()));
        assertEquals(0, new BigDecimal("73400").compareTo(curve.bidSide().get(0).price()));
        assertEquals(0, new BigDecimal("73200").compareTo(curve.bidSide().get(2).price()));
    }

    @Test
    @DisplayName("누적이 크게 뛴 지점을 벽으로 표시한다")
    void detectsWalls() {
        // 3단계에서 100 → 5000 으로 급증
        DepthCurve curve = DepthCurve.from(book(73_500, 100, 100, 5000));

        DepthPoint wall = curve.askSide().get(2);
        assertTrue(wall.wall());
        assertFalse(curve.askSide().get(1).wall());
        assertEquals(2, curve.walls().size(), "매도·매수 양쪽에서 하나씩");
    }

    @Test
    @DisplayName("매수 우위를 백분율로 요약한다")
    void summarisesDominance() {
        List<OrderBookLevel> levels = List.of(
                OrderBookLevel.of(1, new BigDecimal("73600"), 100,
                        new BigDecimal("73400"), 300));
        DepthCurve curve = DepthCurve.from(OrderBook.of("005930", levels, NOW));

        assertEquals(0, new BigDecimal("75.0").compareTo(curve.bidDominancePercent()));
    }

    @Test
    @DisplayName("음성 요약에 누적량과 벽 위치가 들어간다")
    void describesForSpeech() {
        String description = DepthCurve.from(book(73_500, 100, 100, 5000)).describe();

        assertTrue(description.contains("매수 비중"));
        assertTrue(description.contains("물량이 몰린 가격"));
    }

    // ------------------------------------------------------------------
    // 양축 고정
    // ------------------------------------------------------------------

    private DepthChart freshChart() {
        return DepthChart.create(new DepthChartConfig(11, 2, null, 1.3, 0.4));
    }

    @Test
    @DisplayName("첫 호가창에서 두 축을 함께 잡는다")
    void initializesBothAxes() {
        DepthChart.Update update = freshChart().update(book(73_500, 100, 200, 300), null);

        assertTrue(update.chart().isInitialized());
        assertEquals(0, new BigDecimal("100").compareTo(update.chart().tickSize()));
        assertTrue(update.chart().depthScale() >= 600L, "관측 최대값 이상이어야 한다");
        assertFalse(update.view().isEmpty());
    }

    @Test
    @DisplayName("잔량이 조금 변해도 가로축은 그대로 고정된다")
    void keepsDepthAxisStable() {
        DepthChart chart = freshChart().update(book(73_500, 100, 200, 300), null).chart();
        long scaleBefore = chart.depthScale();

        DepthChart.Update update = chart.update(book(73_500, 120, 190, 310), null);

        assertEquals(scaleBefore, update.chart().depthScale());
        assertFalse(update.view().recentered());
    }

    @Test
    @DisplayName("곡선이 가로축을 넘치면 축을 넓히고 알린다")
    void widensDepthAxisWhenExceeded() {
        DepthChart chart = freshChart().update(book(73_500, 100, 200, 300), null).chart();
        long scaleBefore = chart.depthScale();

        DepthChart.Update update = chart.update(book(73_500, 5000, 5000, 5000), null);

        assertTrue(update.chart().depthScale() > scaleBefore);
        assertTrue(update.view().recentered());
        assertTrue(update.view().announcementIfPresent().orElseThrow().contains("잔량 기준"));
    }

    @Test
    @DisplayName("곡선이 너무 작아지면 가로축을 줄인다")
    void shrinksDepthAxisWhenTooSmall() {
        DepthChart chart = freshChart().update(book(73_500, 5000, 5000, 5000), null).chart();
        long scaleBefore = chart.depthScale();

        DepthChart.Update update = chart.update(book(73_500, 100, 100, 100), null);

        assertTrue(update.chart().depthScale() < scaleBefore);
        assertTrue(update.view().recentered());
    }

    @Test
    @DisplayName("가격이 가장자리에 닿으면 세로축을 다시 잡고 알린다")
    void recentersPriceAxis() {
        DepthChart chart = freshChart().update(book(73_500, 100, 200, 300), null).chart();

        DepthChart.Update update = chart.update(book(74_000, 100, 200, 300), new BigDecimal("74000"));

        assertTrue(update.view().recentered());
        assertTrue(update.view().announcementIfPresent().orElseThrow().contains("가격 범위"));
    }

    @Test
    @DisplayName("첫 자리잡기는 알림을 내지 않는다")
    void doesNotAnnounceOnFirstPlacement() {
        DepthChart.Update update = freshChart().update(book(73_500, 100, 200, 300), null);

        assertFalse(update.view().recentered());
        assertTrue(update.view().announcementIfPresent().isEmpty());
    }

    @Test
    @DisplayName("좌표를 0.0~1.0 으로 정규화한다")
    void normalisesCoordinates() {
        DepthChart.Update update = freshChart().update(book(73_500, 100, 200, 300), null);

        for (DepthChartView.Plot plot : update.view().askPoints()) {
            assertTrue(plot.priceRatio() >= 0.0 && plot.priceRatio() <= 1.0);
            assertTrue(plot.depthRatio() >= 0.0 && plot.depthRatio() <= 1.0);
        }
        // 매도는 중심보다 위, 매수는 아래에 놓인다.
        assertTrue(update.view().askPoints().get(0).priceRatio() > 0.5);
        assertTrue(update.view().bidPoints().get(0).priceRatio() < 0.5);
    }

    @Test
    @DisplayName("누적이 커질수록 가로 길이도 길어진다")
    void depthRatioGrowsMonotonically() {
        DepthChart.Update update = freshChart().update(book(73_500, 100, 200, 300), null);
        List<DepthChartView.Plot> asks = update.view().askPoints();

        for (int i = 1; i < asks.size(); i++) {
            assertTrue(asks.get(i).depthRatio() >= asks.get(i - 1).depthRatio());
        }
    }

    @Test
    @DisplayName("세로 범위를 벗어난 점은 그리지 않는다")
    void clipsOutOfRangePoints() {
        // 11행 * 100원 = 중심 ±500원. 8단계는 800원 떨어져 범위 밖이다.
        DepthChart.Update update = freshChart()
                .update(book(73_500, 10, 10, 10, 10, 10, 10, 10, 10), null);

        assertTrue(update.view().askPoints().size() < 8);
        assertTrue(update.view().askPoints().stream()
                .allMatch(plot -> plot.price().compareTo(update.view().highestPrice()) <= 0));
    }

    @Test
    @DisplayName("가로축 기준은 눈에 익은 단위로 올림한다")
    void roundsDepthScale() {
        DepthChart.Update update = freshChart().update(book(73_500, 123, 456, 789), null);

        long scale = update.chart().depthScale();
        assertTrue(scale % 100 == 0 || scale % 1000 == 0, "어중간한 기준: " + scale);
    }

    @Test
    @DisplayName("벽 표시가 화면 좌표까지 전달된다")
    void keepsWallFlagInView() {
        DepthChart.Update update = freshChart().update(book(73_500, 100, 100, 5000), null);

        assertTrue(update.view().askPoints().stream().anyMatch(DepthChartView.Plot::wall));
    }

    @Test
    @DisplayName("빈 호가창은 빈 화면을 돌려주고 축을 망가뜨리지 않는다")
    void handlesEmptyBook() {
        DepthChart.Update update = freshChart()
                .update(OrderBook.of("005930", List.of(), NOW), null);

        assertTrue(update.view().isEmpty());
        assertFalse(update.chart().isInitialized());
    }

    @Test
    @DisplayName("가격이 조금씩 움직이는 동안 축 조정은 드물게 일어난다")
    void rarelyReadjustsUnderNormalMovement() {
        // 대체로 제자리에 머물다 가끔 한 호가씩 움직이는, 실제와 비슷한 흐름.
        java.util.Random random = new java.util.Random(7L);
        DepthChart chart = freshChart();
        long center = 73_500;
        int adjustments = 0;

        for (int i = 0; i < 100; i++) {
            int move = random.nextInt(10);
            if (move == 0) {
                center += 100L;
            } else if (move == 1) {
                center -= 100L;
            }
            DepthChart.Update update = chart.update(book(center, 100, 200, 300), null);
            chart = update.chart();
            if (update.view().recentered()) {
                adjustments++;
            }
        }

        assertTrue(adjustments < 10, "축 조정이 너무 잦다: " + adjustments);
    }
}
