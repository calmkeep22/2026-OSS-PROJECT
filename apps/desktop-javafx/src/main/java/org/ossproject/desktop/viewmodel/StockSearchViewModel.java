package org.ossproject.desktop.viewmodel;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.finance.model.SecuritySummary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 종목검색의 조회·선택·최근검색 상태를 담당한다.
 *
 * <p>종목 목록은 {@link StockQueryPort} 에서만 온다. 화면이 자체 목록을 들고 있으면 실제
 * 연동으로 바꿀 때 어느 값이 진짜인지 구분할 수 없다.
 */
public final class StockSearchViewModel {
    private static final int RESULT_LIMIT = 50;
    private static final int RECENT_LIMIT = 8;

    private final DesktopSession session;
    private final StockQueryPort stocks;
    private final ObservableList<StockSearchItem> items = FXCollections.observableArrayList();
    private final ObservableList<String> recentSearches = FXCollections.observableArrayList();
    private String lastError = "";
    private String currentQuery = "";
    private String currentMarket = "전체";

    public StockSearchViewModel(DesktopSession session, StockQueryPort stocks) {
        this.session = Objects.requireNonNull(session, "session");
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        filter("", "전체");
    }

    public ObservableList<StockSearchItem> items() {
        return items;
    }

    public ObservableList<String> recentSearches() {
        return recentSearches;
    }

    /** 마지막 조회가 실패했을 때의 안내 문구. 성공했으면 빈 문자열이다. */
    public String lastError() {
        return lastError;
    }

    public String currentQuery() {
        return currentQuery;
    }

    public String currentMarket() {
        return currentMarket;
    }

    /**
     * 검색어로 조회하고 시장 구분으로 좁힌다.
     *
     * @return 표시 중인 결과 건수
     */
    public int filter(String query, String market) {
        currentQuery = query == null ? "" : query;
        currentMarket = market == null || market.isBlank() ? "전체" : market;
        List<StockSearchItem> found;
        try {
            found = stocks.search(currentQuery, RESULT_LIMIT).stream()
                    .map(StockSearchItem::of)
                    .filter(item -> item.matchesMarket(currentMarket))
                    .toList();
            lastError = "";
        } catch (RuntimeException failure) {
            // 조회 실패를 빈 목록으로 감추면 "검색 결과 없음"과 구분되지 않는다.
            found = List.of();
            lastError = "종목을 조회하지 못했습니다. 연결 상태를 확인해주세요.";
        }
        items.setAll(found);
        return items.size();
    }

    /** 현재 검색 결과 중 코드나 이름이 정확히 일치하는 종목. */
    public Optional<StockSearchItem> exactMatch(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String normalized = query.strip();
        return items.stream().filter(item -> item.symbol().equalsIgnoreCase(normalized)
                || item.name().equalsIgnoreCase(normalized)).findFirst();
    }

    /** 검색어에 가장 가까운 종목. 없으면 {@code null}. */
    public StockSearchItem findBestMatch(String query) {
        if (query == null || query.isBlank()) return null;
        List<StockSearchItem> found;
        try {
            found = stocks.search(query, RESULT_LIMIT).stream().map(StockSearchItem::of).toList();
        } catch (RuntimeException failure) {
            return null;
        }
        String normalized = query.strip();
        return found.stream()
                .filter(item -> item.symbol().equalsIgnoreCase(normalized)
                        || item.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseGet(() -> found.isEmpty() ? null : found.get(0));
    }

    public void select(StockSearchItem item) {
        Objects.requireNonNull(item, "item");
        session.selectStock(item.toSelection());
        String recent = item.name() + " · " + item.symbol();
        recentSearches.remove(recent);
        recentSearches.add(0, recent);
        if (recentSearches.size() > RECENT_LIMIT) {
            recentSearches.remove(RECENT_LIMIT, recentSearches.size());
        }
    }

    /** 최근 검색 문자열을 다시 종목 선택으로 바꾼다. */
    public boolean selectRecent(String recent) {
        if (recent == null || recent.isBlank()) return false;
        int separator = recent.lastIndexOf(" · ");
        String query = separator < 0 ? recent : recent.substring(separator + 3);
        StockSearchItem item = findBestMatch(query);
        if (item == null) return false;
        select(item);
        return true;
    }

    public void removeRecent(String recent) {
        if (recent != null) recentSearches.remove(recent);
    }

    /**
     * 관심종목에 추가한다. 이미 있으면 {@code false}.
     *
     * <p>그룹은 조회 결과가 알려 준 시장 구분을 쓴다. 종목명을 보고 업종을 추측하지 않는다.
     */
    public boolean addToWatchlist(StockSearchItem item) {
        Objects.requireNonNull(item, "item");
        boolean exists = session.watchlistItems().stream()
                .anyMatch(watchlistItem -> watchlistItem.symbol().equalsIgnoreCase(item.symbol())
                        && watchlistItem.exchange().equalsIgnoreCase(item.exchange()));
        if (exists) return false;

        String group = item.market();
        if (!session.watchlistGroups().contains(group)) {
            session.watchlistGroups().add(group);
        }
        SecuritySummary summary = item.summary();
        session.watchlistItems().add(WatchlistItem.from(group, summary, "없음"));
        return true;
    }
}
