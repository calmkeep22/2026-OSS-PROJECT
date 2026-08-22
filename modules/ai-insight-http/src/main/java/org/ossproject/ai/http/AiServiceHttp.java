package org.ossproject.ai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.ai.AiUnavailableException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 분석 서비스와 주고받는 일만 한다.
 *
 * <p>분석 창구와 뉴스 창구가 따로 있는데 오가는 방식은 같다. 각자 두면 시간 제한과
 * 오류 문장이 조용히 갈라진다 — 한쪽만 고치고 다른 쪽을 잊는다.
 *
 * <p>오류 문장은 사용자에게 그대로 보여 줄 수 있는 말로 만든다. 상태 코드만 적으면
 * 화면을 볼 수 없는 사용자는 무엇을 해야 할지 알 수 없다.
 */
final class AiServiceHttp {

    /** 분석은 167ms 안에 끝난다. 이보다 오래 걸리면 서버가 준비 중이거나 막힌 것이다. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    /**
     * 뉴스 시간 제한.
     *
     * <p>남의 서버(RSS)를 거친다. 실측 11~40초라 30초로는 실제로 걸렸다. 서비스가 결과를
     * 들고 있으므로 이만큼 기다리는 것은 그 종목을 처음 여는 한 번뿐이다.
     */
    private static final Duration NEWS_TIMEOUT = Duration.ofSeconds(90);

    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;

    AiServiceHttp(URI baseUri, HttpClient http, ObjectMapper json) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    JsonNode get(String path) {
        return send(HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(CALL_TIMEOUT).GET().build());
    }

    JsonNode post(String path, String body) {
        return post(path, body, CALL_TIMEOUT);
    }

    JsonNode postSlow(String path, String body) {
        return post(path, body, NEWS_TIMEOUT);
    }

    private JsonNode post(String path, String body, Duration timeout) {
        return send(HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body,
                        java.nio.charset.StandardCharsets.UTF_8)).build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(
                    java.nio.charset.StandardCharsets.UTF_8));
            JsonNode body = parse(response.body());
            if (response.statusCode() >= 400) {
                throw new AiUnavailableException(errorText(response.statusCode(), body));
            }
            return body;
        } catch (IOException error) {
            // 연결 거부는 메시지가 비어 있는 경우가 많다. null 을 그대로 붙이지 않는다.
            throw new AiUnavailableException(
                    "AI 서비스에 연결하지 못했습니다." + detailOf(error), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("AI 분석을 기다리다 중단되었습니다.", error);
        }
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException error) {
            throw new AiUnavailableException("AI 서비스 응답을 읽지 못했습니다.", error);
        }
    }

    /** 사용자가 무엇을 해야 할지 알 수 있는 말로 바꾼다. */
    private static String errorText(int status, JsonNode body) {
        String detail = body.path("detail").asText("");
        return switch (status) {
            case 404 -> "AI 가 모르는 종목입니다. 신규 상장이면 아직 등록되지 않았을 수 있습니다.";
            case 422 -> "분석에 필요한 시세가 부족합니다. " + detail;
            default -> "AI 분석에 실패했습니다. " + (detail.isBlank() ? "상태 코드 " + status : detail);
        };
    }

    /** 예외에서 사용자에게 보여 줄 문장을 뽑는다. */
    static String reasonOf(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "AI 서비스에 연결하지 못했습니다." : message;
    }

    /** 메시지가 없으면 아무것도 붙이지 않는다. "null" 이 화면에 나가면 안 된다. */
    static String detailOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "" : " " + message;
    }

    static String text(JsonNode parent, String field) {
        return text(parent, field, "");
    }

    static String text(JsonNode parent, String field, String fallback) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText("");
        return value.isBlank() ? fallback : value;
    }

    static BigDecimal decimal(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.decimalValue();
    }

    static LocalDate date(JsonNode parent, String field) {
        String raw = text(parent, field);
        try {
            return raw.isBlank() ? null : LocalDate.parse(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 기사 시각.
     *
     * <p>표기가 한 가지가 아니다. 파이썬이 준 문자열은 대개 오프셋을 달고 오지만 아닌
     * 것도 섞인다. 못 읽으면 지금 시각으로 채우지 않는다 — 오래된 기사가 방금 것으로
     * 보이면 사용자는 그것을 새 소식으로 읽는다.
     */
    static Instant instant(JsonNode parent, String field) {
        String raw = text(parent, field);
        if (raw.isBlank()) {
            return null;
        }
        for (java.time.format.DateTimeFormatter format : new java.time.format.DateTimeFormatter[]{
                java.time.format.DateTimeFormatter.ISO_INSTANT,
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME}) {
            try {
                return Instant.from(format.parse(raw.replace(' ', 'T')));
            } catch (RuntimeException ignored) {
                // 다음 표기로 넘어간다.
            }
        }
        return null;
    }

    /**
     * JSON 본문을 만든다.
     *
     * <p>손으로 따옴표를 붙이지 않는다. 사용자가 친 질문이 그대로 들어가는데 거기에
     * 따옴표나 줄바꿈이 있으면 본문이 깨진다. 실제로 서버가 "본문을 읽지 못했습니다"
     * 로만 답해서 원인을 찾기 어렵다.
     */
    String body(java.util.Map<String, Object> fields) {
        try {
            return json.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new AiUnavailableException("보낼 내용을 만들지 못했습니다.", error);
        }
    }
}
