package org.ossproject.desktop.viewmodel;

import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import org.ossproject.desktop.state.WatchlistItem;

import java.util.Objects;

/** 관심종목·그룹·가격 알림의 화면 상태와 변경 규칙. */
public final class WatchlistViewModel {
    public enum GroupDeleteResult { DELETED, NOT_FOUND, IN_USE }

    private final DesktopSession session;

    public WatchlistViewModel(DesktopSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public ObservableList<String> groups() { return session.watchlistGroups(); }
    public ObservableList<WatchlistItem> items() { return session.watchlistItems(); }

    public FilteredList<WatchlistItem> filteredItems() {
        return new FilteredList<>(items(), item -> true);
    }

    public void applyGroupFilter(FilteredList<WatchlistItem> filtered, String group) {
        filtered.setPredicate(item -> group == null || DesktopSession.ALL_GROUP.equals(group)
                || item.group().equals(group));
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
