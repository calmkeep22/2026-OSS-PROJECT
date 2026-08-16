package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.PricePoint;
import org.ossproject.finance.model.StockDetail;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockDetailViewModelTest {

    private StockDetailViewModel viewModel(StockSelection selection) {
        DesktopSession session = new DesktopSession();
        session.selectStock(selection);
        return new StockDetailViewModel(session, new FakeStockQueryAdapter(), new FakeCandleQueryAdapter());
    }

    private static StockSelection apple() {
        return new StockSelection("미국", "AAPL", "Apple", "NASDAQ", "USD");
    }

    @Test void selectedOverseasStockUsesItsOwnCurrencyAndPrice() {
        StockDetailViewModel viewModel = viewModel(apple());

        assertEquals("AAPL", viewModel.detail().symbol());
        assertEquals("Apple", viewModel.detail().name());
        assertEquals("$228.40", viewModel.formatPrice(viewModel.detail().currentPrice()));
    }

    @Test void reportsExactlyWhatTheQueryPortReturns() {
        StockDetailViewModel viewModel = viewModel(apple());
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
        StockDetailViewModel viewModel = viewModel(apple());

        List<PricePoint> history = viewModel.history(StockDetailViewModel.ChartRange.DAY);

        assertEquals(30, history.size());
        assertEquals(viewModel.detail().currentPrice(), history.get(history.size() - 1).close());
    }

    @Test void chartUsesTheCandlesAsReturnedByThePort() {
        StockDetailViewModel viewModel = viewModel(apple());
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

    @Test void koreanStockFormatsInWon() {
        StockDetailViewModel viewModel = viewModel(StockSelection.samsungElectronics());

        assertEquals("73,500원", viewModel.formatPrice(viewModel.detail().currentPrice()));
        assertEquals("73500", viewModel.plainOrderPrice());
    }

    @Test void rejectsUnknownSecurityInsteadOfShowingSubstituteNumbers() {
        StockDetailViewModel viewModel = viewModel(
                new StockSelection("국내", "999999", "없는종목", "KRX", "KRW"));

        assertThrows(IllegalArgumentException.class, viewModel::detail);
    }
}
