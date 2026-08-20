package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.PricePoint;
import org.ossproject.finance.model.StockDetail;

import java.util.List;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class StockDetailViewModelTest {

    private StockDetailViewModel viewModel(StockSelection selection) {
        DesktopSession session = new DesktopSession();
        session.selectStock(selection);
        return new StockDetailViewModel(session,
                new MarketApplicationService(new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(),
                        new FakeMarketDataStreamAdapter(), Runnable::run), Runnable::run);
    }

    private StockDetailViewModel loadedViewModel(StockSelection selection) {
        StockDetailViewModel viewModel = viewModel(selection);
        viewModel.loadInitial().toCompletableFuture().join();
        return viewModel;
    }

    private static StockSelection apple() {
        return new StockSelection("미국", "AAPL", "Apple", "NASDAQ", "USD");
    }

    @Test void selectedOverseasStockUsesItsOwnCurrencyAndPrice() {
        StockDetailViewModel viewModel = loadedViewModel(apple());

        assertEquals("AAPL", viewModel.detail().symbol());
        assertEquals("Apple", viewModel.detail().name());
        assertEquals("$228.40", viewModel.formatPrice(viewModel.detail().currentPrice()));
    }

    @Test void reportsExactlyWhatTheQueryPortReturns() {
        StockDetailViewModel viewModel = loadedViewModel(apple());
        StockDetail reported = new FakeStockQueryAdapter().getDetail("AAPL");
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
        StockDetailViewModel viewModel = loadedViewModel(apple());

        List<PricePoint> history = viewModel.history(StockDetailViewModel.ChartRange.DAY);

        assertEquals(30, history.size());
        assertEquals(viewModel.detail().currentPrice(), history.get(history.size() - 1).close());
    }

    @Test void chartUsesTheCandlesAsReturnedByThePort() {
        StockDetailViewModel viewModel = loadedViewModel(apple());
        List<PricePoint> shown = viewModel.history(StockDetailViewModel.ChartRange.DAY);
        List<PricePoint> reported = new FakeCandleQueryAdapter()
                .getCandles("AAPL", StockDetailViewModel.ChartRange.DAY.interval(),
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
        StockDetailViewModel viewModel = viewModel(apple());

        StockDetailViewModel.InitialData loaded = viewModel.loadInitial().toCompletableFuture().join();

        List<Candle> reported = new FakeCandleQueryAdapter(Clock.fixed(
                loaded.candles().get(loaded.candles().size() - 1).timestamp(), ZoneOffset.UTC))
                .getCandles(
                apple().securityId(), StockDetailViewModel.ChartRange.DAY.interval(),
                StockDetailViewModel.ChartRange.DAY.count());
        assertEquals(reported, loaded.candles());
        assertEquals(loaded.candles(), viewModel.selectedCandles());
        assertEquals(loaded.candles().size(), loaded.chartPoints().size());
        for (int index = 0; index < loaded.candles().size(); index++) {
            assertEquals(loaded.candles().get(index).close(), loaded.chartPoints().get(index).close());
        }
    }

    @Test void selectedChartRangeKeepsItsExactCandlesForAccessiblePlayback() {
        StockDetailViewModel viewModel = loadedViewModel(apple());

        viewModel.loadHistory(StockDetailViewModel.ChartRange.MINUTE_5)
                .toCompletableFuture().join();

        assertEquals(StockDetailViewModel.ChartRange.MINUTE_5, viewModel.selectedChartRange());
        List<Candle> selected = viewModel.selectedCandles();
        assertEquals(new FakeCandleQueryAdapter(Clock.fixed(
                        selected.get(selected.size() - 1).timestamp(), ZoneOffset.UTC)).getCandles(
                        apple().securityId(), StockDetailViewModel.ChartRange.MINUTE_5.interval(),
                        StockDetailViewModel.ChartRange.MINUTE_5.count()),
                selected);
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
}
