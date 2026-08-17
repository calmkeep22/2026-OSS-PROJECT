package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistViewModelTest {
    private WatchlistViewModel viewModel(DesktopSession session) {
        return new WatchlistViewModel(session,
                new MarketApplicationService(new FakeStockQueryAdapter(),
                        new FakeCandleQueryAdapter(), new FakeMarketDataStreamAdapter(), Runnable::run),
                Runnable::run);
    }

    @Test void preventsDeletingUsedGroupAndRenamesRowsTogether() {
        DesktopSession session = new DesktopSession();
        WatchlistViewModel viewModel = viewModel(session);

        assertEquals(WatchlistViewModel.GroupDeleteResult.IN_USE, viewModel.deleteGroup("반도체"));
        assertTrue(viewModel.renameGroup("반도체", "반도체 핵심"));
        assertTrue(viewModel.items().stream().filter(item -> item.securityName().equals("삼성전자"))
                .allMatch(item -> item.group().equals("반도체 핵심")));
    }

    @Test void addsEditsMovesAlertsRemovesAndRefreshesTypedItems() {
        DesktopSession session = new DesktopSession();
        WatchlistViewModel viewModel = viewModel(session);
        WatchlistItem added = new WatchlistItem(
                "미국 기술주", "미국", "AAPL", "Apple", "NASDAQ", "USD", "없음");

        viewModel.save(null, added);
        assertTrue(viewModel.items().contains(added));

        WatchlistItem alerted = viewModel.setAlert(added, "$240");
        assertEquals("$240", alerted.alertText());
        assertTrue(viewModel.move(alerted, -1));

        WatchlistItem edited = alerted.withGroup("AI");
        viewModel.save(alerted, edited);
        assertTrue(viewModel.items().contains(edited));
        assertTrue(viewModel.quoteRows().stream().anyMatch(row -> row.symbol().equals("AAPL")
                && row.displayPrice().equals("$228.40")));

        viewModel.remove(edited);
        assertFalse(viewModel.items().contains(edited));
    }

    @Test void repairsLegacyNameOnlyIdentityWhenQuotesRefresh() {
        DesktopSession session = new DesktopSession();
        session.watchlistItems().setAll(WatchlistItem.legacy("반도체", "삼성전자", "72,500원", "없음"));

        WatchlistViewModel viewModel = viewModel(session);
        viewModel.refresh().toCompletableFuture().join();

        assertEquals("005930", viewModel.items().get(0).symbol());
        assertEquals("KRX", viewModel.items().get(0).exchange());
        assertEquals("73,500원", viewModel.quoteRows().get(0).displayPrice());
    }
}
