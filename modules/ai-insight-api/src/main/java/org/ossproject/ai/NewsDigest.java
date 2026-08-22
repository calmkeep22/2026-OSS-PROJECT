package org.ossproject.ai;

import java.util.List;
import java.util.Optional;

/**
 * 한 종목의 최근 뉴스.
 *
 * <p>{@code sentimentScore} 는 여론의 방향을 요약한 값이지 주가 예측이 아니다. 이
 * 구분을 흐리면 사용자가 지수를 신호로 읽는다. {@code briefing} 마지막 줄에 그
 * 사실이 들어 있으므로 잘라 내지 않는다.
 *
 * <p>기사가 없는 것과 조회에 실패한 것은 다르다. 전자는 {@code articles} 가 비고
 * {@code briefing} 이 그 사실을 말한다. 후자는 애초에 이 값이 만들어지지 않는다.
 *
 * @param events         사건별 요약. 기사 여러 건을 한 문장으로 묶은 것
 * @param marketLine     시황 보도. 사건이 아니라 배경이라 따로 둔다
 * @param sentimentScore 뉴스 감성 지수. 받지 못했으면 비어 있다
 * @param briefing       읽어 줄 전체 문안. 화면이 새로 짓지 않는다
 */
public record NewsDigest(String symbol, String name,
                         Optional<Double> sentimentScore, String sentimentLabel,
                         int positive, int neutral, int negative,
                         List<String> events, Optional<String> marketLine,
                         List<NewsArticle> articles, String briefing) {

    public NewsDigest {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        name = name == null || name.isBlank() ? symbol : name;
        sentimentScore = sentimentScore == null ? Optional.empty() : sentimentScore;
        sentimentLabel = sentimentLabel == null ? "" : sentimentLabel;
        events = List.copyOf(events == null ? List.of() : events);
        marketLine = marketLine == null ? Optional.empty() : marketLine;
        articles = List.copyOf(articles == null ? List.of() : articles);
        briefing = briefing == null || briefing.isBlank()
                ? "관련 뉴스를 찾지 못했습니다." : briefing;
    }

    public boolean isEmpty() {
        return articles.isEmpty() && events.isEmpty();
    }

    /**
     * 감성 지수를 읽어 줄 말.
     *
     * <p>점수만 말하지 않는다. 12 점이 좋은 것인지 나쁜 것인지, 무엇에 대한 점수인지가
     * 숫자에는 없다.
     */
    public Optional<String> sentimentText() {
        return sentimentScore.map(score -> String.format(
                "뉴스 감성 지수 %+.0f점, %s입니다. 여론의 방향을 요약한 값이며 "
                        + "주가 예측이 아닙니다.", score, sentimentLabel));
    }
}
