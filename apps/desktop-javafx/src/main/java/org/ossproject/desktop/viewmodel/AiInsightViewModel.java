package org.ossproject.desktop.viewmodel;

import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AiInsightPort;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecurityId;

import org.ossproject.finance.model.Candle;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * AI 분석을 받아 화면에 넘긴다.
 *
 * <p>봉은 우리가 조회해 함께 보낸다. 분석 서비스가 스스로 조회하게 두면 증권사 시세와
 * 다른 값을 쓰게 되어, 사용자가 화면에서 보는 차트와 분석 근거가 어긋난다.
 *
 * <p>실패를 성공처럼 보이게 하지 않는다. 분석이 없는 것과 "이상 없음" 은 다른 뜻이다.
 */
public final class AiInsightViewModel {

    /**
     * 조회할 봉 수.
     *
     * <p>250봉 이상이면 모든 피처를 쓴다. 그보다 짧으면 서비스가 거절하지 않고 신뢰도를
     * 낮춰 답하는데, 굳이 낮은 신뢰도를 자초할 이유가 없다.
     */
    private static final int BAR_COUNT = 300;

    private final MarketApplicationPort market;
    private final AiInsightPort ai;
    private final Executor stateExecutor;

    private SecurityId inFlight;
    /** 목록 분석 세대. 목록이 새로 잡히면 앞의 것이 남긴 결과를 버린다. */
    private volatile long batchGeneration;

    public AiInsightViewModel(MarketApplicationPort market, AiInsightPort ai, Executor stateExecutor) {
        this.market = Objects.requireNonNull(market, "market");
        this.ai = Objects.requireNonNull(ai, "ai");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
    }

    /** 분석을 받을 수 있는지. 거짓이면 화면은 기능을 감추지 않고 이유를 적는다. */
    public boolean available() {
        return ai.available();
    }

    public String unavailableReason() {
        return ai.unavailableReason();
    }

    /**
     * 한 종목을 분석한다.
     *
     * @param withSimilar 닮은 종목까지 받을지. 목록에서는 끄고 상세에서만 켠다
     * @param onResult    분석 결과. 화면 스레드에서 온다
     * @param onFailure   받지 못한 이유. 사용자에게 그대로 보여 줄 수 있는 문장이다
     */
    public void analyze(SecurityId security, boolean withSimilar,
                        Consumer<AiInsight> onResult, Consumer<String> onFailure) {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(onResult, "onResult");
        Objects.requireNonNull(onFailure, "onFailure");

        inFlight = security;
        market.loadCandles(security, CandleInterval.DAY, BAR_COUNT)
                .thenApply(bars -> ai.brief(security, bars, withSimilar))
                .whenComplete((insight, failure) -> stateExecutor.execute(() -> {
                    // 사용자가 그새 다른 종목을 골랐으면 늦게 온 결과를 버린다.
                    if (!security.equals(inFlight)) {
                        return;
                    }
                    if (failure != null) {
                        onFailure.accept(reasonOf(failure));
                        return;
                    }
                    onResult.accept(insight);
                }));
    }

    /**
     * 여러 종목을 차례로 분석한다.
     *
     * <p>한꺼번에 던지지 않고 하나씩 부른다. 서비스는 스무 종목에 3.3초라 병렬화가 필요
     * 없다고 적어 두었고, 동시에 부르면 같은 모델을 여러 스레드가 함께 만지게 된다.
     *
     * <p>한 종목이 실패해도 멈추지 않는다. 목록 화면에서 하나 때문에 전부 못 보면 그
     * 화면은 쓸 수 없다.
     *
     * <p>결과는 도착하는 대로 하나씩 넘긴다. 다 모아서 한 번에 주면 스무 종목일 때 3초
     * 동안 화면에 아무것도 없다.
     *
     * @param withSimilar 닮은 종목까지 받을지. 목록에서는 꺼야 한다. 종목당 몇 초가 든다
     * @param onEach      한 종목이 끝날 때마다. 화면 스레드에서 온다
     * @param onDone      전부 끝났을 때. 화면 스레드에서 온다
     */
    public void analyzeAll(List<SecurityId> securities, boolean withSimilar,
                           BiConsumer<SecurityId, AiInsight> onEach,
                           BiConsumer<SecurityId, String> onFailure,
                           Runnable onDone) {
        Objects.requireNonNull(securities, "securities");
        Objects.requireNonNull(onEach, "onEach");
        Objects.requireNonNull(onFailure, "onFailure");
        Objects.requireNonNull(onDone, "onDone");

        long batch = ++batchGeneration;
        CompletableFuture.runAsync(() -> {
            for (SecurityId security : securities) {
                // 사용자가 화면을 떠났거나 목록이 새로 잡혔으면 남은 것을 부르지 않는다.
                if (batch != batchGeneration) {
                    return;
                }
                try {
                    List<Candle> bars = market
                            .loadCandles(security, CandleInterval.DAY, BAR_COUNT)
                            .toCompletableFuture().join();
                    AiInsight insight = ai.brief(security, bars, withSimilar);
                    deliver(batch, () -> onEach.accept(security, insight));
                } catch (RuntimeException failure) {
                    deliver(batch, () -> onFailure.accept(security, reasonOf(failure)));
                }
            }
            deliver(batch, onDone);
        });
    }

    private void deliver(long batch, Runnable action) {
        stateExecutor.execute(() -> {
            if (batch == batchGeneration) {
                action.run();
            }
        });
    }

    /** 목록 분석을 그만둔다. 화면을 떠나거나 목록이 바뀔 때 부른다. */
    public void cancelBatch() {
        batchGeneration++;
    }

    /** 분석을 그만 기다린다. 화면을 떠날 때 부른다. */
    public void cancel() {
        inFlight = null;
    }

    /** 사용자가 무엇을 해야 할지 알 수 있는 말로 바꾼다. */
    private static String reasonOf(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof AiUnavailableException) {
            return cause.getMessage();
        }
        String message = cause.getMessage();
        return "AI 분석을 받지 못했습니다." + (message == null || message.isBlank() ? "" : " " + message);
    }
}
