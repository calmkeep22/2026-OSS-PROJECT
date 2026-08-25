package org.ossproject.desktop.ai;

import javafx.scene.Node;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.AiInsightViewModel;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.account.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Coordinates the multi-stock AI panel used by the anomaly screen.
 *
 * <p>The JavaFX application only asks for a panel. Account loading, watchlist merging, server
 * readiness, stable ordering, retry, and per-stock analysis callbacks live here.
 */
public final class AiInsightListCoordinator {
    private final AiInsightViewModel insights;
    private final AiServiceProcess serviceProcess;
    private final Supplier<Account> account;
    private final Supplier<List<WatchlistItem>> watchlist;
    private final Consumer<String> speak;
    private final Consumer<Runnable> uiExecutor;
    private AiInsightListPanel panel;

    public AiInsightListCoordinator(AiInsightViewModel insights,
                                    AiServiceProcess serviceProcess,
                                    Supplier<Account> account,
                                    Supplier<List<WatchlistItem>> watchlist,
                                    Consumer<String> speak,
                                    Consumer<Runnable> uiExecutor) {
        this.insights = Objects.requireNonNull(insights, "insights");
        this.serviceProcess = serviceProcess;
        this.account = Objects.requireNonNull(account, "account");
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.speak = Objects.requireNonNull(speak, "speak");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
    }

    public Node createPanel() {
        panel = new AiInsightListPanel(speak);
        load();
        return panel.root();
    }

    public void load() {
        AiInsightListPanel current = panel;
        if (current == null) return;
        if (!insights.available()) {
            current.unavailable(serviceProcess != null && serviceProcess.running()
                    ? "AI 서버를 준비하고 있습니다. 10초쯤 걸립니다."
                    : insights.unavailableReason(), this::load);
            return;
        }
        current.waiting();
        CompletableFuture.supplyAsync(account)
                .handle((snapshot, failure) -> failure == null ? snapshot : null)
                .thenAccept(snapshot -> uiExecutor.accept(() -> start(current, snapshot)));
    }

    private void start(AiInsightListPanel current, Account snapshot) {
        Map<SecurityId, String> names = new LinkedHashMap<>();
        if (snapshot != null) {
            for (Position position : snapshot.positions()) {
                names.putIfAbsent(SecurityId.of(position.symbol(), "KRX"), position.name());
            }
        }
        for (WatchlistItem item : watchlist.get()) {
            if (!item.needsIdentityRepair()) {
                names.putIfAbsent(item.securityId(), item.securityName());
            }
        }
        if (names.isEmpty()) {
            current.empty("보유 종목이나 관심 종목을 추가하면 AI 분석을 함께 보여 드립니다.");
            return;
        }

        List<SecurityId> securities = List.copyOf(names.keySet());
        List<String> symbols = new ArrayList<>(securities.size());
        for (SecurityId security : securities) symbols.add(security.symbol());
        current.starting(List.copyOf(symbols), List.copyOf(names.values()));
        insights.analyzeAll(securities, false,
                (security, insight) -> current.show(security.symbol(), insight),
                (security, reason) -> current.failed(security.symbol(), reason),
                current::finished);
    }
}
