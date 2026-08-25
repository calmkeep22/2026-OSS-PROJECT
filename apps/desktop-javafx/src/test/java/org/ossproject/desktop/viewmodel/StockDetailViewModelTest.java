package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.PricePoint;
import org.ossproject.finance.model.market.StockDetail;

import java.util.List;
import java.time.Clock;
import java.time.ZoneOffset;
import org.ossproject.finance.model.market.Quote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class StockDetailViewModelTest {

    private final FakeMarketDataStreamAdapter stream = new FakeMarketDataStreamAdapter();

    private StockDetailViewModel viewModel(StockSelection selection) {
        DesktopSession session = new DesktopSession();
        session.selectStock(selection);
        return new StockDetailViewModel(session,
                new MarketApplicationService(new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(),
                        stream, Runnable::run), Runnable::run);
    }

    private StockDetailViewModel loadedViewModel(StockSelection selection) {
        StockDetailViewModel viewModel = viewModel(selection);
        viewModel.loadInitial().toCompletableFuture().join();
        return viewModel;
    }

    private static StockSelection naver() {
        return new StockSelection("국내", "035420", "NAVER", "KRX", "KRW");
    }

    @Test void selectedStockUsesItsOwnPrice() {
        StockDetailViewModel viewModel = loadedViewModel(naver());

        assertEquals("035420", viewModel.detail().symbol());
        assertEquals("NAVER", viewModel.detail().name());
        assertEquals("205,000원", viewModel.formatPrice(viewModel.detail().currentPrice()));
    }

    @Test void reportsExactlyWhatTheQueryPortReturns() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        StockDetail reported = new FakeStockQueryAdapter().getDetail("035420");
        StockDetail shown = viewModel.detail();

        // 화면이 시가·고가·저가·거래량을 현재가에서 만들어 내지 않는지 확인한다.
        assertEquals(reported.currentPrice(), shown.currentPrice());
        assertEquals(reported.open(), shown.open());
        assertEquals(reported.high(), shown.high());
        assertEquals(reported.low(), shown.low());
        assertEquals(reported.volume(), shown.volume());
        assertEquals(reported.changeRate(), shown.changeRate());
    }

    @Test void chartClosesAtTheQuotedPriceWithoutRescaling() {
        StockDetailViewModel viewModel = loadedViewModel(naver());

        List<PricePoint> history = viewModel.history(StockDetailViewModel.ChartRange.DAY);

        // 처음 받을 개수는 구간 정의를 따른다. 여기 숫자를 박아 두면 구간을 넓힐 때마다 깨진다.
        assertEquals(StockDetailViewModel.ChartRange.DAY.count(), history.size());
        assertEquals(viewModel.detail().currentPrice(), history.get(history.size() - 1).close());
    }

    @Test void chartUsesTheCandlesAsReturnedByThePort() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        List<PricePoint> shown = viewModel.history(StockDetailViewModel.ChartRange.DAY);
        List<PricePoint> reported = new FakeCandleQueryAdapter()
                .getCandles("035420", StockDetailViewModel.ChartRange.DAY.interval(),
                        StockDetailViewModel.ChartRange.DAY.count()).stream()
                .map(candle -> candle.toPricePoint(java.time.ZoneId.of("Asia/Seoul")))
                .toList();

        for (int index = 0; index < reported.size(); index++) {
            assertEquals(reported.get(index).close(), shown.get(index).close(),
                    "봉 " + index + " 의 종가가 조회 결과와 달라졌습니다.");
            assertEquals(reported.get(index).high(), shown.get(index).high());
            assertEquals(reported.get(index).low(), shown.get(index).low());
        }
    }

    @Test void visualAndAccessibleChartsShareTheSameCandleSnapshot() {
        StockDetailViewModel viewModel = viewModel(naver());

        StockDetailViewModel.InitialData loaded = viewModel.loadInitial().toCompletableFuture().join();

        List<Candle> reported = new FakeCandleQueryAdapter(Clock.fixed(
                loaded.candles().get(loaded.candles().size() - 1).timestamp(), ZoneOffset.UTC))
                .getCandles(
                naver().securityId(), StockDetailViewModel.ChartRange.DAY.interval(),
                StockDetailViewModel.ChartRange.DAY.count());
        assertEquals(reported, loaded.candles());
        assertEquals(loaded.candles(), viewModel.selectedCandles());
        assertEquals(loaded.candles().size(), loaded.chartPoints().size());
        for (int index = 0; index < loaded.candles().size(); index++) {
            assertEquals(loaded.candles().get(index).close(), loaded.chartPoints().get(index).close());
        }
    }

    @Test void selectedChartRangeKeepsItsExactCandlesForAccessiblePlayback() {
        StockDetailViewModel viewModel = loadedViewModel(naver());

        viewModel.loadHistory(StockDetailViewModel.ChartRange.MINUTE_5)
                .toCompletableFuture().join();

        assertEquals(StockDetailViewModel.ChartRange.MINUTE_5, viewModel.selectedChartRange());
        List<Candle> selected = viewModel.selectedCandles();
        assertEquals(new FakeCandleQueryAdapter(Clock.fixed(
                        selected.get(selected.size() - 1).timestamp(), ZoneOffset.UTC)).getCandles(
                        naver().securityId(), StockDetailViewModel.ChartRange.MINUTE_5.interval(),
                        StockDetailViewModel.ChartRange.MINUTE_5.count()),
                selected);
    }

    /**
     * 끌거나 축소해서 왼쪽 끝에 닿으면 과거를 이어 받는다.
     *
     * <p>가짜 어댑터는 요청한 개수만큼 만들어 주므로, 더 달라고 하면 실제로 늘어난다.
     */
    @Test void loadsOlderCandlesAndKeepsThemInOneSeries() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        int initial = viewModel.history(StockDetailViewModel.ChartRange.DAY).size();

        List<PricePoint> extended = viewModel.loadOlderHistory().toCompletableFuture().join();

        assertTrue(extended.size() > initial, "과거를 더 받아 구간이 늘어야 합니다");
        assertEquals(extended, viewModel.history(StockDetailViewModel.ChartRange.DAY),
                "받아 온 구간이 캐시에도 반영되어야 합니다");
        // 앞에 붙은 것이지 다른 구간으로 바뀐 것이 아니다. 마지막 봉은 그대로여야 한다.
        assertEquals(viewModel.detail().currentPrice(), extended.get(extended.size() - 1).close());
    }

    /**
     * 진행 중인 과거 조회가 있으면 새 요청을 만들지 않는다.
     *
     * <p>끄는 동안 경계에 여러 번 닿으면 요청이 줄줄이 쌓여 호출 한도에 걸린다. 조회가
     * 실제로 대기 중인 상태를 만들어야 확인할 수 있으므로, 조회를 큐에 담아 두고 원할 때만
     * 진행시키는 실행기를 쓴다.
     */
    @Test void reusesTheRunningOlderRequestInsteadOfStackingCalls() {
        List<Runnable> queued = new java.util.ArrayList<>();
        DesktopSession session = new DesktopSession();
        session.selectStock(naver());
        StockDetailViewModel viewModel = new StockDetailViewModel(session,
                new MarketApplicationService(new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(),
                        stream, queued::add), Runnable::run);

        CompletionStage<StockDetailViewModel.InitialData> initial = viewModel.loadInitial();
        drain(queued);
        initial.toCompletableFuture().join();

        // 조회가 큐에 머무는 동안 두 번 요청한다.
        CompletionStage<List<PricePoint>> first = viewModel.loadOlderHistory();
        CompletionStage<List<PricePoint>> second = viewModel.loadOlderHistory();

        assertSame(first, second, "겹친 요청은 진행 중인 것을 그대로 돌려줘야 합니다");
        drain(queued);
        assertTrue(first.toCompletableFuture().join().size()
                > StockDetailViewModel.ChartRange.DAY.count());
    }

    /** 큐에 쌓인 작업을 모두 실행한다. 실행 중에 새로 쌓이는 것도 함께 처리한다. */
    private static void drain(List<Runnable> queued) {
        for (int guard = 0; guard < 100 && !queued.isEmpty(); guard++) {
            List<Runnable> batch = List.copyOf(queued);
            queued.clear();
            batch.forEach(Runnable::run);
        }
    }

    @Test void koreanStockFormatsInWon() {
        StockDetailViewModel viewModel = loadedViewModel(StockSelection.samsungElectronics());

        assertEquals("73,500원", viewModel.formatPrice(viewModel.detail().currentPrice()));
        assertEquals("73500", viewModel.plainOrderPrice());
    }

    @Test void rejectsUnknownSecurityInsteadOfShowingSubstituteNumbers() {
        StockDetailViewModel viewModel = viewModel(
                new StockSelection("국내", "999999", "없는종목", "KRX", "KRW"));

        assertThrows(CompletionException.class,
                () -> viewModel.loadInitial().toCompletableFuture().join());
        assertFalse(viewModel.hasCurrentDetail());
    }

    /**
     * 실시간 체결이 마지막 봉과 화면까지 전달된다.
     *
     * <p>구간 경계 판정과 거래량 차분은 {@code CandleAggregatorTest} 가 도메인에서 덮는다.
     * 여기서는 스트림에서 화면까지 이어졌는지만 본다.
     */
    @Test void liveTradesReachTheChartThroughTheViewModel() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        AtomicReference<List<PricePoint>> pushed = new AtomicReference<>();

        viewModel.startLiveChart(pushed::set);
        assertTrue(stream.subscriptions().contains("035420"), "구독이 시작되어야 합니다");

        stream.emit(new Quote("035420", new BigDecimal("210000"), null, null, null,
                0L, 0L, 10L, Instant.now()));

        assertNotNull(pushed.get(), "화면이 갱신된 지점을 받아야 합니다");
        PricePoint last = pushed.get().get(pushed.get().size() - 1);
        assertEquals(0, new BigDecimal("210000").compareTo(last.close()),
                "마지막 봉의 종가가 체결가여야 합니다");
        assertEquals(pushed.get(), viewModel.history(StockDetailViewModel.ChartRange.DAY),
                "화면에 보낸 지점과 뷰모델 캐시가 같아야 합니다");
    }

    /** 다른 종목의 체결이 지금 보고 있는 차트를 건드리면 안 된다. */
    @Test void ignoresTradesForAnotherSymbol() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        AtomicInteger pushes = new AtomicInteger();
        viewModel.startLiveChart(points -> pushes.incrementAndGet());

        stream.emit(new Quote("005930", new BigDecimal("70000"), null, null, null,
                0L, 0L, 10L, Instant.now()));

        assertEquals(0, pushes.get());
    }

    /** 구독을 놓지 않으면 보이지 않는 차트를 계속 갱신하게 된다. */
    @Test void stoppingReleasesTheSubscription() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        viewModel.startLiveChart(points -> { });

        viewModel.stopLiveChart();

        assertFalse(stream.subscriptions().contains("035420"));
        assertDoesNotThrow(viewModel::stopLiveChart, "여러 번 불러도 안전해야 합니다");
    }

    /** 기간이나 종목을 바꿀 때마다 구독이 쌓이면 체결 한 건에 여러 번 다시 그리게 된다. */
    @Test void restartingDoesNotStackSubscriptions() {
        StockDetailViewModel viewModel = loadedViewModel(naver());
        AtomicInteger pushes = new AtomicInteger();

        viewModel.startLiveChart(points -> pushes.incrementAndGet());
        viewModel.startLiveChart(points -> pushes.incrementAndGet());

        stream.emit(new Quote("035420", new BigDecimal("210000"), null, null, null,
                0L, 0L, 10L, Instant.now()));

        assertEquals(1, pushes.get(), "구독이 하나만 살아 있어야 합니다");
    }
}
