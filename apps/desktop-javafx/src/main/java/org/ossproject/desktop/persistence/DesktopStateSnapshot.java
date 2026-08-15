package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.AlertRule;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.util.List;

/** 비밀정보를 포함하지 않는 데스크톱 화면 상태. */
public record DesktopStateSnapshot(
        List<String> watchlistGroups,
        List<WatchlistItem> watchlistItems,
        List<AlertRule> alertRules,
        List<String> notifications,
        List<JournalEntry> journalEntries,
        StockSelection selectedStock,
        boolean preventDuplicateOrders,
        int maxSubscriptions
) {
    public DesktopStateSnapshot {
        watchlistGroups = watchlistGroups == null ? List.of() : List.copyOf(watchlistGroups);
        watchlistItems = watchlistItems == null ? List.of() : List.copyOf(watchlistItems);
        alertRules = alertRules == null ? List.of() : List.copyOf(alertRules);
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
        journalEntries = journalEntries == null ? List.of() : List.copyOf(journalEntries);
        selectedStock = selectedStock == null ? StockSelection.samsungElectronics() : selectedStock;
        maxSubscriptions = Math.max(1, Math.min(200, maxSubscriptions));
    }
}
