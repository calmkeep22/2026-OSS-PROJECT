package org.ossproject.desktop.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import org.ossproject.desktop.persistence.DesktopStateSnapshot;
import org.ossproject.desktop.state.AlertRule;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.WatchlistItem;

import java.util.Objects;

/** 여러 화면이 공유하는 타입 안전 UI 세션 상태. */
public final class DesktopSession {
    public static final String ALL_GROUP = "전체";

    private final ObjectProperty<StockSelection> selectedStock =
            new SimpleObjectProperty<>(StockSelection.samsungElectronics());
    private final ObservableList<String> watchlistGroups = FXCollections.observableArrayList(
            ALL_GROUP, "반도체", "AI", "배당주", "미국 기술주");
    private final ObservableList<WatchlistItem> watchlistItems = FXCollections.observableArrayList(
            new WatchlistItem("반도체", "국내", "005930", "삼성전자", "KRX", "KRW", "75,000원"),
            new WatchlistItem("반도체", "국내", "000660", "SK하이닉스", "KRX", "KRW", "없음"),
            new WatchlistItem("AI", "국내", "035420", "NAVER", "KRX", "KRW", "200,000원"),
            new WatchlistItem("미국 기술주", "미국", "NVDA", "NVIDIA", "NASDAQ", "USD", "$150"));
    private final ObservableList<AlertRule> alertRules = FXCollections.observableArrayList(
            new AlertRule("삼성전자", "가격 이상", "75,000원", true),
            new AlertRule("SK하이닉스", "등락률 이상", "3.00%", true),
            new AlertRule("테마반도체", "거래량 급증", "평균 3배", false));
    private final ObservableList<String> notifications = FXCollections.observableArrayList(
            "새 알림 · 14:32 · 주문 · 삼성전자 매수 10주 중 5주가 체결되었습니다.",
            "새 알림 · 14:28 · 연결 · 실시간 시세 연결이 복구되었습니다.",
            "새 알림 · 13:55 · 가격 · SK하이닉스가 목표 가격 184,000원에 도달했습니다.",
            "12:42 · 이상 감지 · 테마반도체 거래량이 최근 평균의 3.1배입니다.",
            "11:18 · 주문 · NAVER 매도 3주가 전량 체결되었습니다.");
    private final ObservableList<JournalEntry> journalEntries = FXCollections.observableArrayList(
            new JournalEntry("08/10", "삼성전자", "2,100,000원", "2,200,000원", "+100,000원", "돌파 매매", "수익"),
            new JournalEntry("08/09", "NAVER", "1,300,000원", "1,280,000원", "-20,000원", "재료", "리스크관리"));

    public ObjectProperty<StockSelection> selectedStockProperty() { return selectedStock; }
    public StockSelection selectedStock() { return selectedStock.get(); }
    public void selectStock(StockSelection stock) { selectedStock.set(Objects.requireNonNull(stock, "stock")); }
    public ObservableList<String> watchlistGroups() { return watchlistGroups; }
    public ObservableList<WatchlistItem> watchlistItems() { return watchlistItems; }
    public ObservableList<AlertRule> alertRules() { return alertRules; }
    public ObservableList<String> notifications() { return notifications; }
    public ObservableList<JournalEntry> journalEntries() { return journalEntries; }

    public void restore(DesktopStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        selectedStock.set(snapshot.selectedStock());
        watchlistGroups.setAll(snapshot.watchlistGroups());
        if (!watchlistGroups.contains(ALL_GROUP)) watchlistGroups.add(0, ALL_GROUP);
        watchlistItems.setAll(snapshot.watchlistItems());
        alertRules.setAll(snapshot.alertRules());
        notifications.setAll(snapshot.notifications());
        journalEntries.setAll(snapshot.journalEntries());
    }

    public void onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        watchlistGroups.addListener((ListChangeListener<String>) change -> listener.run());
        watchlistItems.addListener((ListChangeListener<WatchlistItem>) change -> listener.run());
        alertRules.addListener((ListChangeListener<AlertRule>) change -> listener.run());
        notifications.addListener((ListChangeListener<String>) change -> listener.run());
        journalEntries.addListener((ListChangeListener<JournalEntry>) change -> listener.run());
        selectedStock.addListener((obs, old, value) -> listener.run());
    }
}
