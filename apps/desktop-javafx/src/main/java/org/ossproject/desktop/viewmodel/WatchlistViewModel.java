package org.ossproject.desktop.viewmodel;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 관심종목 식별 상태와 최신 조회 시세를 비동기로 결합한다. */
public final class WatchlistViewModel {
    public enum GroupDeleteResult { DELETED, NOT_FOUND, IN_USE }

    public record RefreshResult(int updated, int failed, boolean applied) {
        public String message() {
            if (!applied) return "더 최근의 관심종목 조회 결과를 기다리고 있습니다.";
            if (failed == 0) return "관심종목 " + updated + "개의 최신 시세를 조회했습니다.";
            return "관심종목 " + updated + "개 조회, " + failed + "개 실패. 연결 상태를 확인해주세요.";
        }
    }

    private record ResolvedQuote(WatchlistItem original, WatchlistItem resolved, StockDetail detail) {}

    private final DesktopSession session;
    private final MarketApplicationPort market;
    private final Executor stateExecutor;
    private final AtomicLong refreshSequence = new AtomicLong();
    private final ObservableList<WatchlistQuoteRow> quoteRows = FXCollections.observableArrayList();
    private boolean applyingRefresh;

    public WatchlistViewModel(
            DesktopSession session,
            MarketApplicationPort market,
            Executor stateExecutor
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.market = Objects.requireNonNull(market, "market");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
        items().addListener((ListChangeListener<WatchlistItem>) change -> {
            if (!applyingRefresh) refresh();
        });
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
    public CompletionStage<RefreshResult> refresh() {
        long requestId = refreshSequence.incrementAndGet();
        List<WatchlistItem> snapshot = List.copyOf(items());
        List<CompletableFuture<ResolvedQuote>> requests = snapshot.stream()
                .map(this::resolveQuote)
                .map(CompletionStage::toCompletableFuture)
                .toList();
        CompletableFuture<RefreshResult> result = new CompletableFuture<>();

        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> executeStateChange(result, () -> {
                    if (requestId != refreshSequence.get()) {
                        result.complete(new RefreshResult(quoteRows.size(), 0, false));
                        return;
                    }
                    List<WatchlistQuoteRow> refreshed = new ArrayList<>();
                    int updated = 0;
                    int failed = 0;
                    applyingRefresh = true;
                    try {
                        for (CompletableFuture<ResolvedQuote> request : requests) {
                            ResolvedQuote quote = request.join();
                            WatchlistItem resolved = quote.resolved();
                            int currentIndex = items().indexOf(quote.original());
                            if (currentIndex >= 0 && !resolved.equals(quote.original())) {
                                items().set(currentIndex, resolved);
                            }
                            if (quote.detail() != null) {
                                refreshed.add(WatchlistQuoteRow.available(resolved, quote.detail()));
                                updated++;
                            } else {
                                refreshed.add(WatchlistQuoteRow.unavailable(resolved));
                                failed++;
                            }
                        }
                        quoteRows.setAll(refreshed);
                    } finally {
                        applyingRefresh = false;
                    }
                    result.complete(new RefreshResult(updated, failed, true));
                }));
        return result;
    }

    private CompletionStage<ResolvedQuote> resolveQuote(WatchlistItem item) {
        CompletionStage<WatchlistItem> identity = item.needsIdentityRepair()
                ? market.search(item.securityName(), 20).thenApply(summaries -> repairIdentity(item, summaries))
                : CompletableFuture.completedFuture(item);
        return identity.thenCompose(resolved -> {
            if (resolved.needsIdentityRepair()) {
                return CompletableFuture.completedFuture(new ResolvedQuote(item, resolved, null));
            }
            return market.loadDetail(resolved.securityId())
                    .handle((detail, failure) -> new ResolvedQuote(
                            item, resolved, failure == null ? detail : null));
        }).exceptionally(failure -> new ResolvedQuote(item, item, null));
    }

    private WatchlistItem repairIdentity(WatchlistItem item, List<SecuritySummary> summaries) {
        SecuritySummary match = summaries.stream()
                .filter(summary -> summary.name().equalsIgnoreCase(item.securityName())
                        || summary.symbol().equalsIgnoreCase(item.symbol()))
                .findFirst().orElse(null);
        return match == null ? item : WatchlistItem.from(item.group(), match, item.alertText());
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
        applyingRefresh = true;
        try {
            items().remove(current);
            items().add(target, selected);
        } finally {
            applyingRefresh = false;
        }
        refresh();
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
        applyingRefresh = true;
        try {
            groups().set(index, normalized);
            for (int itemIndex = 0; itemIndex < items().size(); itemIndex++) {
                WatchlistItem item = items().get(itemIndex);
                if (item.group().equals(selected)) items().set(itemIndex, item.withGroup(normalized));
            }
        } finally {
            applyingRefresh = false;
        }
        refresh();
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
