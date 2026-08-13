package org.ossproject.desktop.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;
import org.ossproject.desktop.persistence.DesktopStateSnapshot;

import java.util.Objects;

/**
 * 여러 화면이 공유하는 UI 세션 상태다.
 *
 * <p>View가 서로의 컨트롤을 직접 참조하지 않고 이 상태를 관찰하게 한다.
 * 영속화 단계에서는 이 객체를 저장소와 연결한다.</p>
 */
public final class DesktopSession {
    private final ObjectProperty<StockSelection> selectedStock =
            new SimpleObjectProperty<>(StockSelection.samsungElectronics());
    private final ObservableList<String> watchlistGroups =
            FXCollections.observableArrayList("전체", "반도체", "AI", "배당주", "미국 기술주");
    private final ObservableList<ObservableList<String>> watchlistRows = FXCollections.observableArrayList(
            row("반도체", "삼성전자", "72,500원", "+2.12%", "18,320,122", "75,000원"),
            row("반도체", "SK하이닉스", "184,500원", "+1.42%", "5,821,330", "없음"),
            row("AI", "NAVER", "205,000원", "-0.71%", "1,230,922", "200,000원"),
            row("미국 기술주", "NVIDIA", "$142.65", "+2.34%", "42,381,210", "$150"));
    private final ObservableList<ObservableList<String>> alertRuleRows = FXCollections.observableArrayList(
            row("삼성전자", "가격 이상", "75,000원", "활성"),
            row("SK하이닉스", "등락률 이상", "3.00%", "활성"),
            row("한미반도체", "거래량 급증", "평균 3배", "일시정지"));
    private final ObservableList<String> notifications = FXCollections.observableArrayList(
            "새 알림 · 14:32 · 주문 · 삼성전자 매수 10주 중 5주가 체결되었습니다.",
            "새 알림 · 14:28 · 연결 · 키움 실시간 시세 연결이 복구되었습니다.",
            "새 알림 · 13:55 · 가격 · SK하이닉스가 목표 가격 184,000원에 도달했습니다.",
            "12:42 · 이상 감지 · 한미반도체 거래량이 최근 평균의 3.1배입니다.",
            "11:18 · 주문 · NAVER 매도 3주가 전량 체결되었습니다.");
    private final ObservableList<ObservableList<String>> journalRows = FXCollections.observableArrayList(
            row("08/10", "삼성전자", "2,100,000원", "2,200,000원", "+100,000원", "돌파 매매", "수익"),
            row("08/09", "NAVER", "1,300,000원", "1,280,000원", "-20,000원", "손절", "리스크관리"));

    public ObjectProperty<StockSelection> selectedStockProperty() {
        return selectedStock;
    }

    public StockSelection selectedStock() {
        return selectedStock.get();
    }

    public void selectStock(StockSelection stock) {
        selectedStock.set(stock);
    }

    public ObservableList<String> watchlistGroups() {
        return watchlistGroups;
    }

    public ObservableList<ObservableList<String>> watchlistRows() {
        return watchlistRows;
    }

    public ObservableList<ObservableList<String>> alertRuleRows() {
        return alertRuleRows;
    }

    public ObservableList<String> notifications() {
        return notifications;
    }

    public ObservableList<ObservableList<String>> journalRows() {
        return journalRows;
    }

    public void restore(DesktopStateSnapshot snapshot) {
        selectedStock.set(snapshot.selectedStock());
        replaceStrings(watchlistGroups, snapshot.watchlistGroups());
        replaceRows(watchlistRows, snapshot.watchlistRows());
        replaceRows(alertRuleRows, snapshot.alertRules());
        replaceStrings(notifications, snapshot.notifications());
        replaceRows(journalRows, snapshot.journalRows());
        if (!watchlistGroups.contains("전체")) watchlistGroups.add(0, "전체");
    }

    public void onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        watchlistGroups.addListener((ListChangeListener<String>) change -> listener.run());
        notifications.addListener((ListChangeListener<String>) change -> listener.run());
        observeRows(watchlistRows, listener);
        observeRows(alertRuleRows, listener);
        observeRows(journalRows, listener);
        selectedStock.addListener((obs, old, value) -> listener.run());
    }

    private void observeRows(ObservableList<ObservableList<String>> rows, Runnable listener) {
        rows.forEach(row -> row.addListener((ListChangeListener<String>) change -> listener.run()));
        rows.addListener((ListChangeListener<ObservableList<String>>) change -> {
            while (change.next()) {
                if (change.wasAdded()) change.getAddedSubList().forEach(
                        row -> row.addListener((ListChangeListener<String>) inner -> listener.run()));
            }
            listener.run();
        });
    }

    private void replaceStrings(ObservableList<String> target, java.util.List<String> source) {
        if (!source.isEmpty()) target.setAll(source);
    }

    private void replaceRows(ObservableList<ObservableList<String>> target, java.util.List<java.util.List<String>> source) {
        if (source.isEmpty()) return;
        target.setAll(source.stream().map(this::observableRow).toList());
    }

    private ObservableList<String> observableRow(java.util.List<String> values) {
        ObservableList<String> row = FXCollections.observableArrayList();
        row.addAll(values); return row;
    }

    private static ObservableList<String> row(String... values) {
        return FXCollections.observableArrayList(java.util.List.of(values));
    }
}
