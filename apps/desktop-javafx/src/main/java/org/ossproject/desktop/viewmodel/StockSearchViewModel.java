package org.ossproject.desktop.viewmodel;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.finance.model.SecuritySummary;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 종목검색의 조회·선택·최근검색 상태를 담당한다.
 *
 * <p>종목 목록은 {@link MarketApplicationPort} 에서만 온다. 화면이 자체 목록을 들고 있으면 실제
 * 연동으로 바꿀 때 어느 값이 진짜인지 구분할 수 없다.
 */
public final class StockSearchViewModel {
    private static final int RESULT_LIMIT = 50;
    private static final int RECENT_LIMIT = 8;

    public record SearchResult(int count, String message, boolean applied) {}

    private final DesktopSession session;
    private final MarketApplicationPort market;
    private final Executor stateExecutor;
    private final AtomicLong requestSequence = new AtomicLong();
    private final ObservableList<StockSearchItem> items = FXCollections.observableArrayList();
    private final ObservableList<String> recentSearches = FXCollections.observableArrayList();
    private String lastError = "";
    private String currentQuery = "";
    private String currentMarket = "전체";
    private String preferredSymbol;

    public StockSearchViewModel(
            DesktopSession session,
            MarketApplicationPort market,
            Executor stateExecutor
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.market = Objects.requireNonNull(market, "market");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
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

    /** 화면을 열기 전에 검색어와 시장 선택만 준비한다. 실제 조회는 화면 생성 후 실행한다. */
    public void prepare(String query, String marketFilter) {
        currentQuery = query == null ? "" : query.strip();
        currentMarket = marketFilter == null || marketFilter.isBlank() ? "전체" : marketFilter;
    }

    /**
     * 검색어로 조회하고 시장 구분으로 좁힌다.
     *
     * @return 최신 요청이 화면 상태에 반영된 뒤 완료되는 검색 결과
     */
    public CompletionStage<SearchResult> filter(String query, String marketFilter) {
        currentQuery = query == null ? "" : query.strip();
        currentMarket = marketFilter == null || marketFilter.isBlank() ? "전체" : marketFilter;
        String requestedQuery = currentQuery;
        String requestedMarket = currentMarket;
        long requestId = requestSequence.incrementAndGet();
        CompletableFuture<SearchResult> applied = new CompletableFuture<>();

        if (requestedQuery.isBlank()) {
            executeStateChange(applied, () -> {
                if (requestId != requestSequence.get()) {
                    applied.complete(new SearchResult(items.size(), "", false));
                    return;
                }
                items.clear();
                preferredSymbol = null;
                lastError = "";
                applied.complete(new SearchResult(0, "검색어를 입력해주세요.", true));
            });
            return applied;
        }

        market.search(requestedQuery, RESULT_LIMIT).whenComplete((summaries, failure) ->
                executeStateChange(applied, () -> {
                    if (requestId != requestSequence.get()) {
                        applied.complete(new SearchResult(items.size(), "", false));
                        return;
                    }
                    List<StockSearchItem> found;
                    String message;
                    if (failure != null) {
                        // 조회 실패를 빈 목록으로 감추면 "검색 결과 없음"과 구분되지 않는다.
                        found = List.of();
                        lastError = "종목을 조회하지 못했습니다. 연결 상태를 확인해주세요.";
                        message = lastError;
                    } else {
                        found = summaries.stream()
                                .map(StockSearchItem::of)
                                .filter(item -> item.matchesMarket(requestedMarket))
                                .toList();
                        lastError = "";
                        message = found.isEmpty()
                                ? "검색 결과가 없습니다. 검색어나 시장을 바꿔보세요."
                                : "검색 결과 " + found.size() + "건을 표시했습니다.";
                    }
                    items.setAll(found);
                    applied.complete(new SearchResult(items.size(), message, true));
                }));
        return applied;
    }

    /** 현재 검색 결과 중 코드나 이름이 정확히 일치하는 종목. */
    public Optional<StockSearchItem> exactMatch(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String normalized = query.strip();
        return items.stream().filter(item -> item.symbol().equalsIgnoreCase(normalized)
                || item.name().equalsIgnoreCase(normalized)).findFirst();
    }

    /**
     * 현재 검색 결과 중 종목코드가 정확히 일치하는 종목.
     *
     * <p>종목코드는 유일하므로 이 경우에는 곧바로 상세를 열어도 사용자가 다른 후보를 놓치지
     * 않는다. 종목명은 "한화"처럼 여러 종목의 앞부분과 겹칠 수 있어 따로 구분한다.
     */
    public Optional<StockSearchItem> exactSymbolMatch(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String normalized = query.strip();
        return items.stream().filter(item -> item.symbol().equalsIgnoreCase(normalized)).findFirst();
    }

    /**
     * 목록을 열 때 미리 선택해 둘 종목을 지정한다.
     *
     * <p>검색어와 정확히 일치하는 종목이 있지만 다른 후보도 있을 때 쓴다. 앱이 대신 골라
     * 바로 열어 버리면 화면을 볼 수 없는 사용자는 다른 후보가 있었다는 사실을 알 수 없다.
     * 목록을 보여 주되 해당 종목을 선택해 두면, 한 번 더 확인하고 바로 열 수 있다.
     */
    public void setPreferredSymbol(String symbol) {
        preferredSymbol = symbol == null || symbol.isBlank() ? null : symbol.strip();
    }

    /** 미리 선택해 둘 종목. 지정하지 않았거나 현재 결과에 없으면 비어 있다. */
    public Optional<StockSearchItem> preferredItem() {
        if (preferredSymbol == null) return Optional.empty();
        return items.stream().filter(item -> item.symbol().equalsIgnoreCase(preferredSymbol)).findFirst();
    }

    /** 검색어에 가장 가까운 종목. 없으면 {@code null}. */
    public CompletionStage<StockSearchItem> findBestMatch(String query) {
        if (query == null || query.isBlank()) return CompletableFuture.completedFuture(null);
        String normalized = query.strip();
        CompletableFuture<StockSearchItem> result = new CompletableFuture<>();
        market.search(normalized, RESULT_LIMIT).whenComplete((summaries, failure) ->
                executeStateChange(result, () -> {
                    if (failure != null) {
                        result.complete(null);
                        return;
                    }
                    List<StockSearchItem> found = summaries.stream().map(StockSearchItem::of).toList();
                    StockSearchItem match = found.stream()
                            .filter(item -> item.symbol().equalsIgnoreCase(normalized)
                                    || item.name().equalsIgnoreCase(normalized))
                            .findFirst()
                            .orElseGet(() -> found.isEmpty() ? null : found.get(0));
                    result.complete(match);
                }));
        return result;
    }

    public void select(StockSearchItem item) {
        Objects.requireNonNull(item, "item");
        session.selectStock(item.toSelection());
        addRecent(item.name() + " · " + item.symbol());
    }

    /** 사용자가 실제로 제출한 검색어를 최근 검색에 기록한다. */
    public void recordRecentQuery(String query) {
        if (query == null || query.isBlank()) return;
        addRecent(query.strip());
    }

    private void addRecent(String recent) {
        recentSearches.remove(recent);
        recentSearches.add(0, recent);
        if (recentSearches.size() > RECENT_LIMIT) {
            recentSearches.remove(RECENT_LIMIT, recentSearches.size());
        }
    }

    /** 최근 검색 문자열을 다시 종목 선택으로 바꾼다. */
    public CompletionStage<Boolean> selectRecent(String recent) {
        if (recent == null || recent.isBlank()) return CompletableFuture.completedFuture(false);
        int separator = recent.lastIndexOf(" · ");
        String query = separator < 0 ? recent : recent.substring(separator + 3);
        return findBestMatch(query).thenApply(item -> {
            if (item == null) return false;
            select(item);
            return true;
        });
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

    private void executeStateChange(CompletableFuture<?> result, Runnable change) {
        try {
            stateExecutor.execute(() -> {
                try {
                    change.run();
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }
}
