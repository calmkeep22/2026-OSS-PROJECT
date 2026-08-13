package org.ossproject.desktop.viewmodel;

import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;

import java.util.List;
import java.util.Objects;

/** 관심종목·그룹·가격 알림의 화면 상태와 변경 규칙. */
public final class WatchlistViewModel {
    public enum GroupDeleteResult { DELETED, NOT_FOUND, IN_USE }

    private final DesktopSession session;

    public WatchlistViewModel(DesktopSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public ObservableList<String> groups() { return session.watchlistGroups(); }
    public ObservableList<ObservableList<String>> rows() { return session.watchlistRows(); }

    public FilteredList<ObservableList<String>> filteredRows() {
        return new FilteredList<>(rows(), row -> true);
    }

    public void applyGroupFilter(FilteredList<ObservableList<String>> filtered, String group) {
        filtered.setPredicate(row -> group == null || group.equals("전체") || row.get(0).equals(group));
    }

    public void save(ObservableList<String> existing, List<String> values) {
        if (values.size() != 6 || values.get(1) == null || values.get(1).isBlank()) {
            throw new IllegalArgumentException("종목명 또는 티커는 필수입니다.");
        }
        ObservableList<String> target = existing == null
                ? javafx.collections.FXCollections.observableArrayList()
                : existing;
        target.setAll(values);
        if (existing == null) rows().add(target);
    }

    public void remove(ObservableList<String> selected) {
        if (selected != null) rows().remove(selected);
    }

    public boolean move(ObservableList<String> selected, int direction) {
        if (selected == null) return false;
        int current = rows().indexOf(selected); int target = current + direction;
        if (current < 0 || target < 0 || target >= rows().size()) return false;
        rows().remove(current); rows().add(target, selected); return true;
    }

    public void setAlert(ObservableList<String> selected, String value) {
        if (selected == null) return;
        selected.set(5, value == null || value.isBlank() ? "없음" : value.trim());
    }

    public boolean addGroup(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || groups().contains(normalized)) return false;
        groups().add(normalized); return true;
    }

    public boolean renameGroup(String selected, String replacement) {
        String normalized = replacement == null ? "" : replacement.trim();
        if (selected == null || normalized.isBlank() || groups().contains(normalized)) return false;
        int index = groups().indexOf(selected); if (index < 0) return false;
        groups().set(index, normalized);
        rows().stream().filter(row -> row.get(0).equals(selected)).forEach(row -> row.set(0, normalized));
        return true;
    }

    public GroupDeleteResult deleteGroup(String selected) {
        if (selected == null || !groups().contains(selected) || "전체".equals(selected)) return GroupDeleteResult.NOT_FOUND;
        if (rows().stream().anyMatch(row -> row.get(0).equals(selected))) return GroupDeleteResult.IN_USE;
        groups().remove(selected); return GroupDeleteResult.DELETED;
    }
}
