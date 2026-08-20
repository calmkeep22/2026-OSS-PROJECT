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

    /**
     * 관심종목을 담아 둔 세션.
     *
     * <p>앱은 빈 관심종목으로 시작하므로 테스트가 필요한 기록을 직접 만든다.
     */
    private DesktopSession sessionWith(WatchlistItem... items) {
        DesktopSession session = new DesktopSession();
        for (WatchlistItem item : items) {
            if (!session.watchlistGroups().contains(item.group())) {
                session.watchlistGroups().add(item.group());
            }
        }
        session.watchlistItems().setAll(items);
        return session;
    }

    @Test void preventsDeletingUsedGroupAndRenamesRowsTogether() {
        DesktopSession session = sessionWith(
                new WatchlistItem("반도체", "국내", "005930", "삼성전자", "KRX", "KRW", "75,000원"),
                new WatchlistItem("반도체", "국내", "000660", "SK하이닉스", "KRX", "KRW", "없음"));
        WatchlistViewModel viewModel = viewModel(session);

        assertEquals(WatchlistViewModel.GroupDeleteResult.IN_USE, viewModel.deleteGroup("반도체"));
        assertTrue(viewModel.renameGroup("반도체", "반도체 핵심"));
        assertTrue(viewModel.items().stream().filter(item -> item.securityName().equals("삼성전자"))
                .allMatch(item -> item.group().equals("반도체 핵심")));
    }

    @Test void startsWithNoWatchlistRecords() {
        DesktopSession session = new DesktopSession();

        assertTrue(session.watchlistItems().isEmpty(), "관심종목은 사용자가 담기 전까지 비어 있어야 합니다");
        assertTrue(session.journalEntries().isEmpty());
        assertEquals(java.util.List.of(DesktopSession.ALL_GROUP), session.watchlistGroups());
    }

    @Test void addsEditsMovesAlertsRemovesAndRefreshesTypedItems() {
        DesktopSession session = sessionWith(
                new WatchlistItem("반도체", "국내", "005930", "삼성전자", "KRX", "KRW", "없음"));
        session.watchlistGroups().addAll("미국 기술주", "AI");
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
