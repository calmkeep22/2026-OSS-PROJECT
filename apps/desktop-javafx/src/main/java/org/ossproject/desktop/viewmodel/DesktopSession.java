package org.ossproject.desktop.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.ossproject.desktop.persistence.DesktopStateSnapshot;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.WatchlistItem;

import java.util.Objects;

/** 여러 화면이 공유하는 타입 안전 UI 세션 상태. */
public final class DesktopSession {
    public static final String ALL_GROUP = "전체";

    private final ObjectProperty<StockSelection> selectedStock =
            new SimpleObjectProperty<>(StockSelection.samsungElectronics());
    // 관심종목·알림 목록·매매일지는 모두 사용자가 만드는 기록이다. 예전에는
    // 화면이 비어 보이지 않도록 삼성전자와 체결 알림 같은 표본을 미리 넣어 두었는데,
    // 화면을 볼 수 없는 사용자는 그것이 자기 기록인지 앱이 넣어 둔 예시인지 구분할 수 없다.
    // 빈 상태로 시작하고 저장된 기록이 있으면 restore 가 채운다.
    private final ObservableList<String> watchlistGroups = FXCollections.observableArrayList(ALL_GROUP);
    private final ObservableList<WatchlistItem> watchlistItems = FXCollections.observableArrayList();
    private final ObservableList<String> notifications = FXCollections.observableArrayList();
    private final ObservableList<JournalEntry> journalEntries = FXCollections.observableArrayList();

    public ObjectProperty<StockSelection> selectedStockProperty() { return selectedStock; }
    public StockSelection selectedStock() { return selectedStock.get(); }
    public void selectStock(StockSelection stock) { selectedStock.set(Objects.requireNonNull(stock, "stock")); }
    public ObservableList<String> watchlistGroups() { return watchlistGroups; }
    public ObservableList<WatchlistItem> watchlistItems() { return watchlistItems; }
    public ObservableList<String> notifications() { return notifications; }
    public ObservableList<JournalEntry> journalEntries() { return journalEntries; }

    public void restore(DesktopStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        selectedStock.set(snapshot.selectedStock());
        watchlistGroups.setAll(snapshot.watchlistGroups());
        if (!watchlistGroups.contains(ALL_GROUP)) watchlistGroups.add(0, ALL_GROUP);
        watchlistItems.setAll(snapshot.watchlistItems());
        notifications.setAll(snapshot.notifications());
        journalEntries.setAll(snapshot.journalEntries());
    }

    public void onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        watchlistGroups.addListener((ListChangeListener<String>) change -> listener.run());
        watchlistItems.addListener((ListChangeListener<WatchlistItem>) change -> listener.run());
        notifications.addListener((ListChangeListener<String>) change -> listener.run());
        journalEntries.addListener((ListChangeListener<JournalEntry>) change -> listener.run());
        selectedStock.addListener((obs, old, value) -> listener.run());
    }
}
