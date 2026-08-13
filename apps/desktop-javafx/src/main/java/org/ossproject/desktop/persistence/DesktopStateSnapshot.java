package org.ossproject.desktop.persistence;

import org.ossproject.desktop.viewmodel.StockSelection;

import java.util.List;

/** 비밀정보를 포함하지 않는 데스크톱 UI 영속 상태. */
public record DesktopStateSnapshot(
        List<String> watchlistGroups,
        List<List<String>> watchlistRows,
        List<List<String>> alertRules,
        List<String> notifications,
        List<List<String>> journalRows,
        StockSelection selectedStock,
        boolean speechEnabled,
        boolean soundEnabled,
        boolean keyboardGuidanceEnabled,
        boolean reducedMotionEnabled,
        boolean largeTextEnabled,
        boolean highContrastEnabled,
        String informationDensity,
        String voiceName,
        double speechRate,
        int speechVolume,
        boolean preventDuplicateOrders,
        int maxSubscriptions
) {
    public DesktopStateSnapshot {
        watchlistGroups = List.copyOf(watchlistGroups);
        watchlistRows = copyRows(watchlistRows);
        alertRules = copyRows(alertRules);
        notifications = List.copyOf(notifications);
        journalRows = copyRows(journalRows);
        selectedStock = selectedStock == null ? StockSelection.samsungElectronics() : selectedStock;
        informationDensity = informationDensity == null || informationDensity.isBlank() ? "표준" : informationDensity;
        voiceName = voiceName == null ? "" : voiceName;
        speechRate = Math.max(0.5, Math.min(2.0, speechRate));
        speechVolume = Math.max(0, Math.min(100, speechVolume));
        maxSubscriptions = Math.max(1, Math.min(200, maxSubscriptions));
    }

    private static List<List<String>> copyRows(List<List<String>> rows) {
        return rows.stream().map(List::copyOf).toList();
    }
}
