package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StockSearchViewModelTest {
    @Test void filtersByTextAndMarketAndSharesSelection() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = new StockSearchViewModel(session);

        assertEquals(1, viewModel.filter("NVDA", "미국"));
        StockSearchItem item = viewModel.items().get(0);
        viewModel.select(item);

        assertEquals("NVDA", session.selectedStock().symbol());
        assertEquals("NVIDIA · NVDA", viewModel.recentSearches().get(0));
    }

    @Test void doesNotAddDuplicateWatchlistItem() {
        DesktopSession session = new DesktopSession();
        StockSearchViewModel viewModel = new StockSearchViewModel(session);
        StockSearchItem samsung = viewModel.findBestMatch("005930");

        assertFalse(viewModel.addToWatchlist(samsung));
    }
}
