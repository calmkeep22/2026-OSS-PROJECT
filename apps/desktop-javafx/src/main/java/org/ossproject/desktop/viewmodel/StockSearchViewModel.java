package org.ossproject.desktop.viewmodel;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import org.ossproject.desktop.state.WatchlistItem;

import java.util.Objects;

/** 종목검색의 필터·선택·최근검색 상태를 담당한다. */
public final class StockSearchViewModel {
    private final DesktopSession session;
    private final ObservableList<StockSearchItem> allItems = FXCollections.observableArrayList(
            new StockSearchItem("국내", "005930", "삼성전자", "KRX", "72,500원", "+2.12%"),
            new StockSearchItem("국내", "000660", "SK하이닉스", "KRX", "184,500원", "+1.42%"),
            new StockSearchItem("국내", "035420", "NAVER", "KRX", "205,000원", "-0.71%"),
            new StockSearchItem("미국", "AAPL", "Apple", "NASDAQ", "$228.40", "+0.83%"),
            new StockSearchItem("미국", "NVDA", "NVIDIA", "NASDAQ", "$142.65", "+2.34%"),
            new StockSearchItem("ETF", "069500", "KODEX 200", "KRX", "36,120원", "+1.02%"));
    private final FilteredList<StockSearchItem> filteredItems = new FilteredList<>(allItems, item -> true);
    private final ObservableList<String> recentSearches = FXCollections.observableArrayList(
            "삼성전자 · 005930", "NVIDIA · NVDA", "SK하이닉스 · 000660");

    public StockSearchViewModel(DesktopSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public FilteredList<StockSearchItem> items() {
        return filteredItems;
    }

    public ObservableList<String> recentSearches() {
        return recentSearches;
    }

    public int filter(String query, String market) {
        filteredItems.setPredicate(item -> item.matches(query, market));
        return filteredItems.size();
    }

    public StockSearchItem findBestMatch(String query) {
        if (query == null || query.isBlank()) return null;
        String normalized = query.strip().toLowerCase(java.util.Locale.ROOT);
        return allItems.stream()
                .filter(item -> item.symbol().equalsIgnoreCase(normalized) || item.name().equalsIgnoreCase(query.strip()))
                .findFirst()
                .orElseGet(() -> allItems.stream().filter(item -> item.matches(query, "전체")).findFirst().orElse(null));
    }

    public void select(StockSearchItem item) {
        Objects.requireNonNull(item, "item");
        session.selectStock(item.toSelection());
        String recent = item.name() + " · " + item.symbol();
        recentSearches.remove(recent);
        recentSearches.add(0, recent);
        if (recentSearches.size() > 8) recentSearches.remove(8, recentSearches.size());
    }

    public boolean addToWatchlist(StockSearchItem item) {
        Objects.requireNonNull(item, "item");
        boolean exists = session.watchlistItems().stream()
                .anyMatch(watchlistItem -> watchlistItem.securityName().equalsIgnoreCase(item.name()));
        if (exists) return false;
        String group = switch (item.market()) {
            case "미국" -> "미국 기술주";
            case "ETF" -> "배당주";
            default -> item.name().equals("NAVER") ? "AI" : "반도체";
        };
        session.watchlistItems().add(new WatchlistItem(
                group, item.name(), item.price(), item.changeRate(), "0", "없음"));
        return true;
    }
}
