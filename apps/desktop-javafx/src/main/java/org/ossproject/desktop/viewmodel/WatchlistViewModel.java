package org.ossproject.desktop.viewmodel;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.finance.model.SecuritySummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 관심종목 식별 상태와 최신 조회 시세를 결합한다. */
public final class WatchlistViewModel {
    public enum GroupDeleteResult { DELETED, NOT_FOUND, IN_USE }
    public record RefreshResult(int updated, int failed) {
        public String message() {
            if (failed == 0) return "관심종목 " + updated + "개의 최신 시세를 조회했습니다.";
            return "관심종목 " + updated + "개 조회, " + failed + "개 실패. 연결 상태를 확인해주세요.";
        }
    }

    private final DesktopSession session;
    private final StockQueryPort stocks;
    private final ObservableList<WatchlistQuoteRow> quoteRows = FXCollections.observableArrayList();
    private boolean refreshing;

    public WatchlistViewModel(DesktopSession session, StockQueryPort stocks) {
        this.session = Objects.requireNonNull(session, "session");
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        items().addListener((ListChangeListener<WatchlistItem>) change -> {
            if (!refreshing) refresh();
        });
        refresh();
    }

    public ObservableList<String> groups() { return session.watchlistGroups(); }
    public ObservableList<WatchlistItem> items() { return session.watchlistItems(); }
    public ObservableList<WatchlistQuoteRow> quoteRows() { return quoteRows; }

    public FilteredList<WatchlistQuoteRow> filteredRows() {
        return new FilteredList<>(quoteRows, row -> true);
    }

    public void applyGroupFilter(FilteredList<WatchlistQuoteRow> filtered, String group) {
        filtered.setPredicate(row -> group == null || DesktopSession.ALL_GROUP.equals(group)
                || row.group().equals(group));
    }

    /** 저장된 종목 식별자로 최신 상세를 다시 조회한다. 일부 실패도 행에서 명시한다. */
    public RefreshResult refresh() {
        if (refreshing) return new RefreshResult(quoteRows.size(), 0);
        refreshing = true;
        try {
            List<WatchlistQuoteRow> refreshed = new ArrayList<>();
            int updated = 0;
            int failed = 0;
            for (int index = 0; index < items().size(); index++) {
                WatchlistItem original = items().get(index);
                WatchlistItem resolved = repairLegacyIdentity(original);
                if (!resolved.equals(original)) items().set(index, resolved);
                try {
                    refreshed.add(WatchlistQuoteRow.available(resolved, stocks.getDetail(resolved.symbol())));
                    updated++;
                } catch (RuntimeException unavailable) {
                    refreshed.add(WatchlistQuoteRow.unavailable(resolved));
                    failed++;
                }
            }
            quoteRows.setAll(refreshed);
            return new RefreshResult(updated, failed);
        } finally {
            refreshing = false;
        }
    }

    private WatchlistItem repairLegacyIdentity(WatchlistItem item) {
        if (!item.needsIdentityRepair()) return item;
        try {
            SecuritySummary match = stocks.search(item.securityName(), 20).stream()
                    .filter(summary -> summary.name().equalsIgnoreCase(item.securityName())
                            || summary.symbol().equalsIgnoreCase(item.symbol()))
                    .findFirst().orElse(null);
            return match == null ? item : WatchlistItem.from(item.group(), match, item.alertText());
        } catch (RuntimeException unavailable) {
            return item;
        }
    }

    public void save(WatchlistItem existing, WatchlistItem replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (existing == null) {
            items().add(replacement);
            return;
        }
        int index = items().indexOf(existing);
        if (index < 0) throw new IllegalArgumentException("수정할 관심종목을 찾을 수 없습니다.");
        items().set(index, replacement);
    }

    public void remove(WatchlistItem selected) {
        if (selected != null) items().remove(selected);
    }

    public boolean move(WatchlistItem selected, int direction) {
        if (selected == null) return false;
        int current = items().indexOf(selected);
        int target = current + direction;
        if (current < 0 || target < 0 || target >= items().size()) return false;
        items().remove(current);
        items().add(target, selected);
        return true;
    }

    public WatchlistItem setAlert(WatchlistItem selected, String value) {
        if (selected == null) return null;
        WatchlistItem replacement = selected.withAlertText(value);
        save(selected, replacement);
        return replacement;
    }

    public boolean addGroup(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || groups().contains(normalized)) return false;
        groups().add(normalized);
        return true;
    }

    public boolean renameGroup(String selected, String replacement) {
        String normalized = replacement == null ? "" : replacement.trim();
        if (selected == null || normalized.isBlank() || groups().contains(normalized)) return false;
        int index = groups().indexOf(selected);
        if (index < 0 || DesktopSession.ALL_GROUP.equals(selected)) return false;
        groups().set(index, normalized);
        for (int itemIndex = 0; itemIndex < items().size(); itemIndex++) {
            WatchlistItem item = items().get(itemIndex);
            if (item.group().equals(selected)) items().set(itemIndex, item.withGroup(normalized));
        }
        return true;
    }

    public GroupDeleteResult deleteGroup(String selected) {
        if (selected == null || !groups().contains(selected) || DesktopSession.ALL_GROUP.equals(selected)) {
            return GroupDeleteResult.NOT_FOUND;
        }
        if (items().stream().anyMatch(item -> item.group().equals(selected))) return GroupDeleteResult.IN_USE;
        groups().remove(selected);
        return GroupDeleteResult.DELETED;
    }
}
