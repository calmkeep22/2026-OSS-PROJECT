package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.Exchange;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderBookLevel;
import org.ossproject.finance.model.PriceLadderView;
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
    private final OrderBookViewModel viewModel = new OrderBookViewModel(market, Runnable::run);

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
}
