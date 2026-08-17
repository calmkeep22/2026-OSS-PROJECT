package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockSearchViewModelTest {

    private StockSearchViewModel viewModel(DesktopSession session) {
        return new StockSearchViewModel(session, market(new FakeStockQueryAdapter()), Runnable::run);
    }

    private MarketApplicationPort market(StockQueryPort stocks) {
        return new MarketApplicationService(stocks, new FakeCandleQueryAdapter(),
                new FakeMarketDataStreamAdapter(), Runnable::run);
    }

    @Test void filtersByTextAndMarketAndSharesSelection() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = viewModel(session);

        assertEquals(1, viewModel.filter("NVDA", "미국").toCompletableFuture().join().count());
        StockSearchItem item = viewModel.items().get(0);
        viewModel.select(item);

        assertEquals("NVDA", session.selectedStock().symbol());
        assertEquals("NVIDIA · NVDA", viewModel.recentSearches().get(0));
    }

    @Test void doesNotAddDuplicateWatchlistItem() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = viewModel(session);
        StockSearchItem samsung = viewModel.findBestMatch("005930").toCompletableFuture().join();

        assertNotNull(samsung);
        assertFalse(viewModel.addToWatchlist(samsung));
    }

    @Test void showsPricesReportedByTheQueryPortWithoutRewritingThem() {
        StockSearchViewModel viewModel = viewModel(new DesktopSession());
        StockQueryPort port = new FakeStockQueryAdapter();

        viewModel.filter("005930", "전체").toCompletableFuture().join();
        StockSearchItem item = viewModel.items().get(0);
        SecuritySummary reported = port.search("005930", 1).get(0);

        assertEquals(reported.currentPrice(), item.summary().currentPrice());
        assertEquals("73,500원", item.price());
        assertEquals("+3.23%", item.changeRate());
    }

    @Test void reportsQueryFailureInsteadOfShowingAnEmptyResult() {
        StockSearchViewModel viewModel = new StockSearchViewModel(new DesktopSession(), market(new StockQueryPort() {
            @Override public List<SecuritySummary> search(String query, int limit) {
                throw new IllegalStateException("연결 끊김");
            }

            @Override public StockDetail getDetail(String symbol) {
                throw new IllegalStateException("연결 끊김");
            }
        }), Runnable::run);

        assertEquals(0, viewModel.filter("삼성", "전체").toCompletableFuture().join().count());
        assertFalse(viewModel.lastError().isBlank());
    }

    @Test void keepsSearchContextAndCanOpenAndDeleteARecentSearch() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = viewModel(session);

        viewModel.filter("NV", "미국").toCompletableFuture().join();
        viewModel.select(viewModel.items().get(0));
        String recent = viewModel.recentSearches().get(0);
        viewModel.filter("삼성", "국내").toCompletableFuture().join();

        assertEquals("삼성", viewModel.currentQuery());
        assertEquals("국내", viewModel.currentMarket());
        assertTrue(viewModel.selectRecent(recent).toCompletableFuture().join());
        assertEquals("NVDA", session.selectedStock().symbol());

        viewModel.removeRecent(recent);
        assertFalse(viewModel.recentSearches().contains(recent));
    }
}
