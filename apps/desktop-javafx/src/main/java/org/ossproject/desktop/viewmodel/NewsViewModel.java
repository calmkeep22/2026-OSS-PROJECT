package org.ossproject.desktop.viewmodel;

import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.ai.NewsDigest;
import org.ossproject.ai.NewsPort;
import org.ossproject.finance.model.SecurityId;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 뉴스와 질의응답을 받아 화면에 넘긴다.
 *
 * <p>뉴스는 남의 서버(RSS)를 거쳐 몇 초가 걸린다. 화면 스레드에서 부르면 그동안 앱이
 * 통째로 멈춘다. 스크린리더 사용자는 멈춘 것과 느린 것을 구별하지 못해 앱이 죽은 줄
 * 알고 닫는다.
 *
 * <p>실패를 성공처럼 보이게 하지 않는다. 뉴스가 없는 것과 받지 못한 것은 다른 뜻이다.
 */
public final class NewsViewModel {

    private final NewsPort news;
    private final Executor stateExecutor;

    private SecurityId inFlight;

    public NewsViewModel(NewsPort news, Executor stateExecutor) {
        this.news = Objects.requireNonNull(news, "news");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
    }

    /**
     * 한 종목의 뉴스를 받는다.
     *
     * @param onFailure 받지 못한 이유. 사용자에게 그대로 보여 줄 수 있는 문장이다
     */
    public void load(SecurityId security, Consumer<NewsDigest> onResult,
                     Consumer<String> onFailure) {
        Objects.requireNonNull(security, "security");
        inFlight = security;
        CompletableFuture.supplyAsync(() -> news.news(security))
                .whenComplete((digest, failure) -> stateExecutor.execute(() -> {
                    // 사용자가 그새 다른 종목을 골랐으면 늦게 온 결과를 버린다.
                    if (!security.equals(inFlight)) {
                        return;
                    }
                    if (failure != null) {
                        onFailure.accept(reasonOf(failure));
                        return;
                    }
                    onResult.accept(digest);
                }));
    }

    /**
     * 질문 하나를 보낸다.
     *
     * @param context 화면이 이미 보여 주고 있는 분석. 서버가 다시 계산하면 그새 값이
     *                바뀌어 사용자가 보고 있는 것과 다른 답을 듣는다
     */
    public void ask(SecurityId security, String question, AiInsight context,
                    Consumer<ChatAnswer> onAnswer) {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(onAnswer, "onAnswer");
        CompletableFuture.supplyAsync(() -> news.ask(security, question, context))
                .whenComplete((answer, failure) -> stateExecutor.execute(() -> {
                    if (failure != null) {
                        // 답을 못 받은 것도 답 자리에 적는다. 조용히 사라지면 사용자는
                        // 자기 질문이 안 보내진 줄 알고 같은 것을 다시 묻는다.
                        onAnswer.accept(new ChatAnswer(reasonOf(failure), List.of(), true, List.of()));
                        return;
                    }
                    onAnswer.accept(answer);
                }));
    }

    /**
     * 미리 받아 둘 종목을 알려 준다.
     *
     * <p>구글 뉴스 RSS 는 최근 7일까지만 준다. 오늘 안 받으면 그날치는 영영 없다. 앱이
     * 아는 보유·관심 종목을 넘겨 두면 서비스가 하루 한 번 훑어 쌓는다.
     */
    public void track(List<SecurityId> securities) {
        if (securities == null || securities.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> news.track(securities));
    }

    /** 화면을 떠날 때 부른다. 늦게 오는 결과를 버리게 한다. */
    public void cancel() {
        inFlight = null;
    }

    private static String reasonOf(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof AiUnavailableException) {
            return cause.getMessage();
        }
        String message = cause.getMessage();
        return "뉴스를 받지 못했습니다." + (message == null || message.isBlank() ? "" : " " + message);
    }
}
