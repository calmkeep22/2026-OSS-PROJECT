package org.ossproject.ai;

import java.time.Instant;
import java.util.Optional;

/**
 * 기사 한 건.
 *
 * <p>{@code sentiment} 는 이 기사 하나의 논조다. 종목 전체의 감성 지수와 다르다. 한
 * 기사가 부정이어도 지수는 긍정일 수 있다.
 *
 * @param sentiment 서비스가 매긴 논조. 못 매겼으면 비어 있다
 */
public record NewsArticle(String title, String source, Instant publishedAt,
                          String url, Optional<String> sentiment) {

    public NewsArticle {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("제목은 필수입니다.");
        }
        source = source == null || source.isBlank() ? "출처 미상" : source;
        url = url == null ? "" : url;
        sentiment = sentiment == null ? Optional.empty() : sentiment;
    }

    /** 목록에서 한 줄로 읽어 줄 말. 출처와 시각을 앞에 둔다. */
    public String describe() {
        return source + " · " + title;
    }
}
