package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockSearchViewModelTest {

    private StockSearchViewModel viewModel(DesktopSession session) {
        return new StockSearchViewModel(session, new FakeStockQueryAdapter());
    }

    @Test void filtersByTextAndMarketAndSharesSelection() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = viewModel(session);

        assertEquals(1, viewModel.filter("NVDA", "미국"));
        StockSearchItem item = viewModel.items().get(0);
        viewModel.select(item);

        assertEquals("NVDA", session.selectedStock().symbol());
        assertEquals("NVIDIA · NVDA", viewModel.recentSearches().get(0));
    }

    @Test void doesNotAddDuplicateWatchlistItem() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = viewModel(session);
        StockSearchItem samsung = viewModel.findBestMatch("005930");

        assertNotNull(samsung);
        assertFalse(viewModel.addToWatchlist(samsung));
    }

    @Test void showsPricesReportedByTheQueryPortWithoutRewritingThem() {
        StockSearchViewModel viewModel = viewModel(new DesktopSession());
        StockQueryPort port = new FakeStockQueryAdapter();

        viewModel.filter("005930", "전체");
        StockSearchItem item = viewModel.items().get(0);
        SecuritySummary reported = port.search("005930", 1).get(0);

        assertEquals(reported.currentPrice(), item.summary().currentPrice());
        assertEquals("73,500원", item.price());
        assertEquals("+3.23%", item.changeRate());
    }

    @Test void reportsQueryFailureInsteadOfShowingAnEmptyResult() {
        StockSearchViewModel viewModel = new StockSearchViewModel(new DesktopSession(), new StockQueryPort() {
            @Override public List<SecuritySummary> search(String query, int limit) {
                throw new IllegalStateException("연결 끊김");
            }

            @Override public StockDetail getDetail(String symbol) {
                throw new IllegalStateException("연결 끊김");
            }
        });

        assertEquals(0, viewModel.filter("삼성", "전체"));
        assertFalse(viewModel.lastError().isBlank());
    }
}
