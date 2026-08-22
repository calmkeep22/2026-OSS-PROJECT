package org.ossproject.ai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.ai.NewsArticle;
import org.ossproject.ai.NewsDigest;
import org.ossproject.ai.NewsPort;
import org.ossproject.finance.model.SecurityId;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 뉴스와 질의응답을 HTTP 로 받아 온다.
 *
 * <p>분석과 나눈 이유는 실패 범위가 다르기 때문이다. 뉴스는 남의 서버(RSS)를 거치므로
 * 예측·이상감지가 멀쩡해도 혼자 실패한다. 한 창구로 묶으면 뉴스가 안 될 때 분석까지
 * 못 쓰는 것처럼 보인다.
 */
public final class HttpNewsAdapter implements NewsPort {

    /**
     * 목록에 올릴 기사 수.
     *
     * <p>서비스는 스무 건까지 준다. 화면을 볼 수 없는 사용자는 목록을 훑지 못하고 위에서
     * 아래로 듣는다. 스무 건이면 끝까지 가기 전에 무엇을 듣고 있었는지 잊는다.
     */
    private static final int MAX_ARTICLES = 8;

    private final AiServiceHttp service;

    public HttpNewsAdapter(URI baseUri) {
        this(baseUri, AiServiceHttp.defaultClient(), new ObjectMapper());
    }

    HttpNewsAdapter(URI baseUri, HttpClient http, ObjectMapper json) {
        this.service = new AiServiceHttp(baseUri, http, json);
    }

    @Override
    public NewsDigest news(SecurityId security) {
        Objects.requireNonNull(security, "security");
        // 뉴스는 RSS 를 거쳐 몇 초가 걸린다. 분석과 같은 시간 제한을 쓰면 늘 초과한다.
        JsonNode root = service.postSlow("/news",
                service.body(Map.of("code", security.symbol(), "days", 7)));
        return toDigest(security, root);
    }

    @Override
    public ChatAnswer ask(SecurityId security, String question, AiInsight context) {
        Objects.requireNonNull(security, "security");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("질문은 필수입니다.");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("code", security.symbol());
        fields.put("question", question);
        // 화면이 이미 보여 주고 있는 분석을 함께 보낸다. 서버가 다시 계산하면 그새 값이
        // 바뀌어 사용자가 보고 있는 것과 다른 답을 듣는다.
        if (context != null) {
            fields.put("narration", context.narration());
            fields.put("forecast", context.forecast().map(f -> f.narration()).orElse(""));
            fields.put("direction", context.directionText().orElse(""));
            fields.put("direction_meaningful",
                    context.directionForecast().map(f -> f.meaningful()).orElse(false));
            fields.put("risk", context.riskText().orElse(""));
            fields.put("anomaly", context.anomaly().map(a -> a.grade()).orElse(""));
        }
        JsonNode root = service.postSlow("/chat", service.body(fields));
        return new ChatAnswer(
                AiServiceHttp.text(root, "답변", "답을 받지 못했습니다."),
                strings(root.path("근거")),
                root.path("거절").asBoolean(false),
                strings(root.path("추천질문")));
    }

    @Override
    public void track(List<SecurityId> securities) {
        if (securities == null || securities.isEmpty()) {
            return;
        }
        List<String> codes = new ArrayList<>();
        for (SecurityId security : securities) {
            codes.add(security.symbol());
        }
        try {
            service.post("/news/track", service.body(Map.of("codes", codes)));
        } catch (RuntimeException ignored) {
            // 미리받기는 사용자가 기다리는 일이 아니다. 실패해도 화면이 멈출 이유가 없다.
        }
    }

    private NewsDigest toDigest(SecurityId security, JsonNode root) {
        BigDecimal score = AiServiceHttp.decimal(root, "지수");
        List<NewsArticle> articles = new ArrayList<>();
        for (JsonNode entry : root.path("기사")) {
            if (articles.size() == MAX_ARTICLES) {
                break;
            }
            String title = AiServiceHttp.text(entry, "제목");
            if (title.isBlank()) {
                continue;
            }
            articles.add(new NewsArticle(title,
                    AiServiceHttp.text(entry, "출처"),
                    AiServiceHttp.instant(entry, "시각"),
                    AiServiceHttp.text(entry, "주소"),
                    optional(AiServiceHttp.text(entry, "감성"))));
        }
        return new NewsDigest(
                AiServiceHttp.text(root, "종목코드", security.symbol()),
                AiServiceHttp.text(root, "종목명", security.symbol()),
                score == null ? Optional.empty() : Optional.of(score.doubleValue()),
                AiServiceHttp.text(root, "지수해석"),
                root.path("긍정").asInt(0), root.path("중립").asInt(0),
                root.path("부정").asInt(0),
                strings(root.path("사건")),
                optional(AiServiceHttp.text(root, "시황")),
                List.copyOf(articles),
                AiServiceHttp.text(root, "브리핑", "관련 뉴스를 찾지 못했습니다."));
    }

    private static Optional<String> optional(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            String value = entry.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }
}
