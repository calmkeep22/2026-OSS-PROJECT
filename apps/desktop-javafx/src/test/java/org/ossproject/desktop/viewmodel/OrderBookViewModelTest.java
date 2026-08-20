package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.DepthChartView;
import org.ossproject.finance.model.Exchange;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderBookLevel;
import org.ossproject.finance.model.PriceLadderView;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.SecurityId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookViewModelTest {

    private static final SecurityId SAMSUNG = new SecurityId("005930", Exchange.KRX);
    private static final SecurityId HYNIX = new SecurityId("000660", Exchange.KRX);

    private final FakeMarketDataStreamAdapter stream = new FakeMarketDataStreamAdapter();
    private final AtomicReference<OrderBook> snapshot = new AtomicReference<>();
    private final MarketApplicationService market = new MarketApplicationService(
            new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(),
            symbol -> snapshot.get(), stream,
            Runnable::run, Runnable::run, java.time.Clock.systemUTC());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-20T05:00:00Z"));
    private final OrderBookViewModel viewModel = new OrderBookViewModel(market, Runnable::run,
            org.ossproject.finance.model.PriceLadderConfig.defaults(),
            org.ossproject.finance.model.DepthChartConfig.defaults(), clock);

    /** 시간을 손으로 밀어 실시간이 멈춘 상황을 만든다. */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration amount) {
            now = now.plus(amount);
        }

        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static OrderBook book(String symbol, long bidSize) {
        return OrderBook.of(symbol, List.of(
                OrderBookLevel.of(1, new BigDecimal("70100"), 120L, new BigDecimal("70000"), bidSize),
                OrderBookLevel.of(2, new BigDecimal("70200"), 80L, new BigDecimal("69900"), 90L)
        ), Instant.parse("2026-08-20T05:00:00Z"));
    }

    @Test void buildsALadderFromTheFirstOrderBook() {
        AtomicReference<PriceLadderView> pushed = new AtomicReference<>();
        viewModel.start(SAMSUNG, pushed::set);

        stream.emitOrderBook(book("005930", 200L));

        PriceLadderView view = pushed.get();
        assertNotNull(view, "화면이 갱신된 사다리를 받아야 합니다");
        assertEquals("005930", view.symbol());
        assertFalse(view.rows().isEmpty());
        assertTrue(view.currentPriceRow().isPresent(), "기준가 행이 있어야 키보드 탐색을 시작할 수 있습니다");
    }

    /** 가격 축이 고정되어야 저시력 사용자가 보던 자리를 잃지 않는다. */
    @Test void keepsThePriceAxisStillWhileSizesChange() {
        AtomicReference<PriceLadderView> pushed = new AtomicReference<>();
        viewModel.start(SAMSUNG, pushed::set);

        stream.emitOrderBook(book("005930", 200L));
        List<java.math.BigDecimal> first = pushed.get().rows().stream().map(r -> r.price()).toList();

        stream.emitOrderBook(book("005930", 900L));
        List<java.math.BigDecimal> second = pushed.get().rows().stream().map(r -> r.price()).toList();

        assertEquals(first, second, "잔량만 바뀌면 가격 축은 그대로여야 합니다");
        assertFalse(pushed.get().recentered());
    }

    @Test void ignoresOrderBooksForAnotherSymbol() {
        AtomicInteger pushes = new AtomicInteger();
        viewModel.start(SAMSUNG, view -> pushes.incrementAndGet());

        stream.emitOrderBook(book("000660", 200L));

        assertEquals(0, pushes.get());
    }

    /** 이전 종목의 가격 축을 물려받으면 기준가가 격자 밖이라 첫 화면이 비어 보인다. */
    @Test void startsAFreshLadderWhenTheStockChanges() {
        viewModel.start(SAMSUNG, view -> { });
        stream.emitOrderBook(book("005930", 200L));
        assertTrue(viewModel.currentView().isPresent());

        viewModel.start(HYNIX, view -> { });

        assertTrue(viewModel.currentView().isEmpty(), "종목이 바뀌면 이전 사다리를 버려야 합니다");
    }

    @Test void stoppingReleasesTheSubscription() {
        viewModel.start(SAMSUNG, view -> { });
        assertTrue(stream.subscriptions().contains("005930"));

        viewModel.stop();

        assertFalse(stream.subscriptions().contains("005930"));
        assertDoesNotThrow(viewModel::stop, "여러 번 불러도 안전해야 합니다");
    }

    @Test void restartingDoesNotStackSubscriptions() {
        AtomicInteger pushes = new AtomicInteger();
        viewModel.start(SAMSUNG, view -> pushes.incrementAndGet());
        viewModel.start(SAMSUNG, view -> pushes.incrementAndGet());

        stream.emitOrderBook(book("005930", 200L));

        assertEquals(1, pushes.get(), "구독이 하나만 살아 있어야 합니다");
    }

    /**
     * 실시간만 붙이면 다음 호가가 올 때까지 화면이 비어 있다. 장 시간 외에는 영영 오지
     * 않으므로 화면을 열 때 한 장을 받아 둔다.
     */
    @Test void showsAQueriedSnapshotBeforeAnyRealtimeUpdateArrives() {
        snapshot.set(book("005930", 200L));
        AtomicReference<PriceLadderView> pushed = new AtomicReference<>();

        viewModel.start(SAMSUNG, pushed::set);

        assertNotNull(pushed.get(), "구독을 시작하면 조회한 호가가 먼저 보여야 합니다");
        assertFalse(pushed.get().rows().isEmpty());
    }

    /** 조회가 실패해도 구독은 살려 둔다. 지금 못 받는 것과 앞으로도 못 받는 것은 다르다. */
    @Test void keepsTheSubscriptionWhenTheSnapshotQueryFails() {
        MarketApplicationService failing = new MarketApplicationService(
                new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(),
                symbol -> { throw new IllegalStateException("조회 실패"); }, stream,
                Runnable::run, Runnable::run, java.time.Clock.systemUTC());
        OrderBookViewModel model = new OrderBookViewModel(failing, Runnable::run);
        AtomicReference<PriceLadderView> pushed = new AtomicReference<>();

        assertDoesNotThrow(() -> model.start(SAMSUNG, pushed::set));
        assertTrue(stream.subscriptions().contains("005930"));

        stream.emitOrderBook(book("005930", 200L));

        assertNotNull(pushed.get(), "이후 실시간 호가는 받아야 합니다");
    }

    /** 사다리와 그래프는 같은 호가창에서 나온다. 따로 갱신하면 두 표현이 어긋난다. */
    @Test void updatesTheLadderAndTheDepthGraphFromTheSameOrderBook() {
        AtomicReference<PriceLadderView> ladder = new AtomicReference<>();
        AtomicReference<DepthChartView> depth = new AtomicReference<>();

        viewModel.start(SAMSUNG, ladder::set, depth::set);
        stream.emitOrderBook(book("005930", 200L));

        assertNotNull(ladder.get());
        assertNotNull(depth.get());
        assertEquals("005930", depth.get().symbol());
        assertFalse(depth.get().isEmpty(), "그래프에도 점이 있어야 합니다");
    }

    /** 그래프 좌표는 도메인이 0.0~1.0 으로 정규화해 준다. 화면은 폭과 높이만 곱한다. */
    @Test void handsOutNormalisedCoordinatesTheScreenCanDrawDirectly() {
        AtomicReference<DepthChartView> depth = new AtomicReference<>();
        viewModel.start(SAMSUNG, view -> { }, depth::set);

        stream.emitOrderBook(book("005930", 200L));

        depth.get().askPoints().forEach(plot -> {
            assertTrue(plot.priceRatio() >= 0.0 && plot.priceRatio() <= 1.0);
            assertTrue(plot.depthRatio() >= 0.0 && plot.depthRatio() <= 1.0);
        });
    }

    @Test void startsAFreshDepthGraphWhenTheStockChanges() {
        viewModel.start(SAMSUNG, view -> { }, view -> { });
        stream.emitOrderBook(book("005930", 200L));
        assertTrue(viewModel.currentDepthView().isPresent());

        viewModel.start(HYNIX, view -> { }, view -> { });

        assertTrue(viewModel.currentDepthView().isEmpty(), "종목이 바뀌면 그래프도 새로 잡아야 합니다");
    }

    /**
     * 격자 중심은 체결가로 잡는다. 호가 중간값은 스프레드가 벌어지면 체결가와 달라지고,
     * 체결이 나도 호가가 그대로면 움직이지 않는다.
     */
    @Test void centersTheLadderOnTheLastTradedPriceWhenOneIsKnown() {
        AtomicReference<PriceLadderView> pushed = new AtomicReference<>();
        viewModel.start(SAMSUNG, pushed::set);

        stream.emit(new Quote("005930", new BigDecimal("70000"), null, null, null,
                0L, 0L, 10L, Instant.now()));
        stream.emitOrderBook(book("005930", 200L));

        assertTrue(viewModel.centeredOnTradedPrice());
        assertEquals(0, new BigDecimal("70000").compareTo(
                        pushed.get().currentPriceRow().orElseThrow().price()),
                "체결가가 있으면 그 가격이 중심이어야 합니다");
    }

    /** 체결가를 아직 못 받았으면 호가 중간값으로 물러선다. 이름은 화면이 구분한다. */
    @Test void fallsBackToTheMidPriceBeforeAnyTradeArrives() {
        AtomicReference<PriceLadderView> pushed = new AtomicReference<>();
        viewModel.start(SAMSUNG, pushed::set);

        stream.emitOrderBook(book("005930", 200L));

        assertFalse(viewModel.centeredOnTradedPrice());
        assertTrue(pushed.get().currentPriceRow().isPresent());
    }

    @Test void forgetsTheTradedPriceWhenTheStockChanges() {
        viewModel.start(SAMSUNG, view -> { });
        stream.emit(new Quote("005930", new BigDecimal("70000"), null, null, null,
                0L, 0L, 10L, Instant.now()));
        assertTrue(viewModel.centeredOnTradedPrice());

        viewModel.start(HYNIX, view -> { });

        assertFalse(viewModel.centeredOnTradedPrice(), "이전 종목의 체결가를 쓰면 안 됩니다");
    }

    @Test void stoppingReleasesTheQuoteSubscriptionToo() {
        viewModel.start(SAMSUNG, view -> { });
        assertTrue(stream.subscriptions().contains("005930"));

        viewModel.stop();

        assertFalse(stream.subscriptions().contains("005930"),
                "호가와 체결 구독을 모두 놓아야 합니다");
    }

    /**
     * 연결 상태만으로는 알 수 없다. 연결은 되어 있는데 데이터가 오지 않는 경우가 있다.
     * 장이 닫히면 그렇다.
     */
    @Test void reportsStaleOnceOrderBooksStopArriving() {
        java.time.Duration staleAfter = java.time.Duration.ofSeconds(30);
        viewModel.start(SAMSUNG, view -> { });
        assertFalse(viewModel.isLive(staleAfter), "아직 한 건도 못 받았으면 살아 있지 않습니다");

        stream.emitOrderBook(book("005930", 200L));
        assertTrue(viewModel.isLive(staleAfter));

        clock.advance(java.time.Duration.ofSeconds(31));

        assertFalse(viewModel.isLive(staleAfter), "한동안 호가가 없으면 멈춘 것으로 봅니다");
    }

    @Test void becomesLiveAgainWhenOrderBooksResume() {
        java.time.Duration staleAfter = java.time.Duration.ofSeconds(30);
        viewModel.start(SAMSUNG, view -> { });
        stream.emitOrderBook(book("005930", 200L));
        clock.advance(java.time.Duration.ofSeconds(31));
        assertFalse(viewModel.isLive(staleAfter));

        stream.emitOrderBook(book("005930", 300L));

        assertTrue(viewModel.isLive(staleAfter));
    }

    /** 종목이 바뀌면 이전 종목의 수신 시각으로 살아 있다고 하면 안 된다. */
    @Test void forgetsTheLastReceiptWhenTheStockChanges() {
        viewModel.start(SAMSUNG, view -> { });
        stream.emitOrderBook(book("005930", 200L));

        viewModel.start(HYNIX, view -> { });

        assertFalse(viewModel.isLive(java.time.Duration.ofSeconds(30)));
    }

    @Test void handsOutWallAnnouncementsOnlyWhenTheyChange() {
        AtomicReference<java.util.Optional<String>> said = new AtomicReference<>();
        viewModel.start(SAMSUNG, view -> { }, view -> { }, said::set);

        stream.emitOrderBook(book("005930", 200L));
        assertNotNull(said.get(), "안내 통로가 호출되어야 합니다");
    }
}
