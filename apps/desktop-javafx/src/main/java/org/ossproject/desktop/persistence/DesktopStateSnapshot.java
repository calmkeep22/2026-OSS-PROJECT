package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.util.List;

/** 비밀정보를 포함하지 않는 데스크톱 화면 상태. */
public record DesktopStateSnapshot(
        List<String> watchlistGroups,
        List<WatchlistItem> watchlistItems,
        List<String> recentSearches,
        List<String> notifications,
        List<JournalEntry> journalEntries,
        StockSelection selectedStock,
        boolean preventDuplicateOrders
) {
    public DesktopStateSnapshot {
        watchlistGroups = watchlistGroups == null ? List.of() : List.copyOf(watchlistGroups);
        watchlistItems = watchlistItems == null ? List.of() : List.copyOf(watchlistItems);
        recentSearches = recentSearches == null ? List.of() : List.copyOf(recentSearches);
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
        journalEntries = journalEntries == null ? List.of() : List.copyOf(journalEntries);
        selectedStock = selectedStock == null ? StockSelection.samsungElectronics() : selectedStock;
    }
}
