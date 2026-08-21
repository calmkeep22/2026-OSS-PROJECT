package org.ossproject.desktop.viewmodel;

import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AiInsightPort;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecurityId;

import java.util.Objects;
import java.util.concurrent.Executor;
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
