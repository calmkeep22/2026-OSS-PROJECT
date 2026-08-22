package org.ossproject.ai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AiInsightPort;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.ai.AnomalySignal;
import org.ossproject.ai.Confidence;
import org.ossproject.ai.Forecast;
import org.ossproject.ai.SimilarOutlook;
import org.ossproject.ai.SimilarStock;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.SecurityId;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 파이썬 분석 서비스를 HTTP 로 부른다.
 *
 * <p>봉을 함께 보낸다. 서비스가 스스로 조회하게 두면 증권사 시세와 다른 값을 쓰게 되어,
 * 사용자가 화면에서 보는 차트와 분석 근거가 어긋난다.
 *
 * <p>장중 미완성 봉은 {@link TradingSessionBars} 가 걸러 낸다.
 */
public final class HttpAiInsightAdapter implements AiInsightPort {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 분석은 167ms 안에 끝난다. 이보다 오래 걸리면 서버가 준비 중이거나 막힌 것이다. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    /**
     * 읽어 줄 닮은 종목 수.
     *
     * <p>서비스는 다섯 개를 준다. 다섯 개면 종목명과 숫자 둘씩 열 개를 연달아 듣게 되어
     * 앞의 것을 기억하지 못한 채 끝난다. {@code brief} 도 요약에서 셋만 남긴다.
     */
    private static final int MAX_SIMILAR = 3;

    private final URI baseUri;
    private final HttpClient http;
    private final ObjectMapper json;
    private final TradingSessionBars sessionBars;

    private volatile String unavailableReason = "AI 서비스 상태를 아직 확인하지 않았습니다.";

    public HttpAiInsightAdapter(URI baseUri, Clock clock) {
        this(baseUri, clock, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                new ObjectMapper());
    }

    HttpAiInsightAdapter(URI baseUri, Clock clock, HttpClient http, ObjectMapper json) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
        this.sessionBars = new TradingSessionBars(Objects.requireNonNull(clock, "clock"));
    }

    @Override
    public boolean available() {
        try {
            JsonNode report = get("/health");
            if (!report.path("전체정상").asBoolean(false)) {
                String reason = report.path("사유").asText("");
                unavailableReason = reason.isBlank()
                        ? "AI 서비스가 준비되지 않았습니다. 모델 파일과 지수 데이터를 확인해주세요."
                        : "AI 서비스가 준비되지 않았습니다. " + reason;
                return false;
            }
            unavailableReason = "";
            return true;
        } catch (RuntimeException error) {
            // 예외가 이미 사람이 읽을 문장을 담고 있다. 앞에 또 붙이면 같은 말이 두 번 나온다.
            unavailableReason = reasonOf(error);
            return false;
        }
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public AiInsight brief(SecurityId security, List<Candle> bars, boolean withSimilar) {
        Objects.requireNonNull(security, "security");
        List<Candle> settled = sessionBars.settled(bars);
        if (settled.isEmpty()) {
            throw new AiUnavailableException(
                    "분석에 넣을 확정된 봉이 없습니다. 장 마감 뒤에 다시 시도해주세요.");
        }
        JsonNode brief = post("/brief", requestBody(security, settled, withSimilar, "변동성"));
        // 방향은 brief 가 담지 않는다. 따로 받아 함께 보여 준다. 검증에서 우연과 구별되지
        // 않았으므로 그 사실도 값에 실려 온다. 실패해도 나머지를 버리지 않는다.
        Optional<Forecast> direction = Optional.empty();
        try {
            direction = toForecast(post("/predict",
                    requestBody(security, settled, false, "방향")));
        } catch (RuntimeException ignored) {
            // 방향을 못 받아도 변동성과 이상감지는 그대로 쓴다.
        }
        // brief 의 유사종목은 종목명·코드·유사도만 추린 요약이다. 함께 움직인 정도와
        // 닮은 구간 다음에 무슨 일이 있었는지, 그리고 서비스가 쓴 단서가 빠진다. 셋 다
        // 사용자에게 보여야 하는 값이라 상세에서는 통째로 받는다.
        JsonNode similarNode = null;
        if (withSimilar) {
            try {
                similarNode = post("/similar", requestBody(security, settled, true, "변동성"));
            } catch (RuntimeException ignored) {
                // 못 받으면 brief 가 준 요약으로 되돌아간다. 유사도 하나 때문에 예측과
                // 이상감지까지 잃을 이유가 없다.
            }
        }
        return toInsight(security, brief, direction, similarNode);
    }

    private String requestBody(SecurityId security, List<Candle> bars, boolean withSimilar,
                               String target) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":\"").append(security.symbol()).append("\",")
                .append("\"with_similar\":").append(withSimilar).append(",")
                .append("\"target\":\"").append(target).append("\",")
                // 뉴스를 켜면 1~3초가 되고 거의 전부 RSS 요청이다. 화면이 기다릴 시간이 아니다.
                .append("\"with_news\":false,")
                .append("\"bars\":[");
        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"date\":\"").append(bar.timestamp().atZone(SEOUL).toLocalDate()).append("\",")
                    .append("\"open\":").append(bar.open().toPlainString()).append(",")
                    .append("\"high\":").append(bar.high().toPlainString()).append(",")
                    .append("\"low\":").append(bar.low().toPlainString()).append(",")
                    .append("\"close\":").append(bar.close().toPlainString()).append(",")
                    .append("\"volume\":").append(bar.volume()).append("}");
        }
        return sb.append("]}").toString();
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(CALL_TIMEOUT).GET().build());
    }

    private JsonNode post(String path, String body) {
        return send(HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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

    /** 예외에서 사용자에게 보여 줄 문장을 뽑는다. */
    private static String reasonOf(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "AI 서비스에 연결하지 못했습니다." : message;
    }

    /** 메시지가 없으면 아무것도 붙이지 않는다. "null" 이 화면에 나가면 안 된다. */
    private static String detailOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "" : " " + message;
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException error) {
            throw new AiUnavailableException("AI 서비스 응답을 읽지 못했습니다.", error);
        }
    }

    /** 사용자가 무엇을 해야 할지 알 수 있는 말로 바꾼다. */
    private String errorText(int status, JsonNode body) {
        String detail = body.path("detail").asText("");
        return switch (status) {
            case 404 -> "AI 가 모르는 종목입니다. 신규 상장이면 아직 등록되지 않았을 수 있습니다.";
            case 422 -> "분석에 필요한 시세가 부족합니다. " + detail;
            default -> "AI 분석에 실패했습니다. " + (detail.isBlank() ? "상태 코드 " + status : detail);
        };
    }

    private AiInsight toInsight(SecurityId security, JsonNode root, Optional<Forecast> direction,
                                JsonNode similarNode) {
        JsonNode forecastNode = root.path("예측");
        String narration = text(root, "문안");
        if (narration.isBlank()) {
            throw new AiUnavailableException("AI 응답에 읽어 줄 문장이 없습니다.");
        }
        return new AiInsight(
                text(root, "종목코드", security.symbol()),
                text(root, "종목명", security.symbol()),
                narration,
                Confidence.from(text(forecastNode, "신뢰도")),
                // 방향 예측은 검증에서 우연과 구별되지 않았다. 서비스가 그 사실을 실어 보낸다.
                forecastNode.path("유의미").asBoolean(false),
                toForecast(forecastNode),
                direction,
                toAnomaly(root.path("이상감지")),
                similarNode == null ? toSimilar(root.path("유사종목"))
                        : toSimilar(similarNode.path("results")),
                toOutlook(similarNode),
                toFailures(root.path("오류")));
    }

    /**
     * 판정과 같은 쪽을 가리키는 확률.
     *
     * <p>확률 이름이 타깃에 따라 바뀐다. 아무거나 집으면 "하락" 판정에 상승확률 49.3
     * 퍼센트가 붙어, 판정과 숫자가 서로 반대를 가리킨다. 화면을 볼 수 없는 사용자는
     * 그 모순을 확인할 방법이 없다.
     *
     * <p>서비스는 임계값을 50 으로 옮긴 뒤 이 값들을 계산하므로, 판정에 맞는 쪽을
     * 고르기만 하면 언제나 50 을 넘는다.
     */
    private static BigDecimal probabilityFor(JsonNode node, String verdict) {
        return switch (verdict) {
            case "크게움직임" -> decimal(node, "크게움직임확률");
            case "잔잔함" -> decimal(node, "잔잔함확률");
            case "상승" -> decimal(node, "상승확률");
            case "하락" -> decimal(node, "하락확률");
            default -> null;
        };
    }

    private Optional<Forecast> toForecast(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        String target = text(node, "타깃");
        String verdict = text(node, "예측");
        if (target.isBlank() || verdict.isBlank()) {
            return Optional.empty();
        }
        BigDecimal probability = probabilityFor(node, verdict);
        if (probability == null) {
            // 판정에 맞는 숫자를 못 찾으면 예측을 통째로 접는다. 판정과 반대쪽 확률을
            // 보여 주는 것은 아무것도 안 보여 주는 것보다 나쁘다. 문안은 그대로 나간다.
            return Optional.empty();
        }
        return Optional.of(new Forecast(target, verdict, probability,
                date(node, "대상일"), node.path("금일여부").asBoolean(false),
                node.path("유의미").asBoolean(false)));
    }

    private Optional<AnomalySignal> toAnomaly(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        JsonNode risk = node.path("위험도");
        BigDecimal change = decimal(node, "등락률");
        return Optional.of(new AnomalySignal(
                node.path("이상").asBoolean(false),
                text(node, "등급"),
                text(node, "방향"),
                date(node, "기준일"),
                change == null ? BigDecimal.ZERO : change,
                text(risk, "등급"),
                text(risk, "조언")));
    }

    /**
     * 닮은 종목 목록.
     *
     * <p>{@code /brief} 요약과 {@code /similar} 원본을 둘 다 받는다. 필드 이름이 다르다
     * — 요약은 한국어 키를, 원본은 {@code code}/{@code name}/{@code similarity} 를 쓴다.
     * 둘을 각각 읽으면 어느 쪽이 왔는지에 따라 화면이 달라진다.
     */
    private List<SimilarStock> toSimilar(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<SimilarStock> stocks = new ArrayList<>();
        for (JsonNode entry : node) {
            if (stocks.size() == MAX_SIMILAR) {
                break;
            }
            String symbol = text(entry, "종목코드");
            if (symbol.isBlank()) {
                symbol = text(entry, "code");
            }
            if (symbol.isBlank()) {
                continue;
            }
            String name = text(entry, "종목명");
            if (name.isBlank()) {
                name = text(entry, "name", symbol);
            }
            // 유사도는 0~1 비율로 온다. 화면은 퍼센트로 읽으므로 여기서 옮긴다.
            BigDecimal score = decimal(entry, "유사도");
            if (score == null) {
                score = decimal(entry, "similarity");
            }
            stocks.add(new SimilarStock(symbol, name, percent(score),
                    Optional.ofNullable(decimal(entry, "동조도")).map(HttpAiInsightAdapter::percent),
                    // 어디가 어떻게 닮았는지는 서비스가 문장으로 준다. 화면이 성분 숫자를
                    // 보고 말을 지어내면 서비스가 쓴 것과 다른 근거가 된다.
                    text(entry, "explain")));
        }
        return List.copyOf(stocks);
    }

    /** 0~1 비율을 퍼센트로. 없으면 0 으로 둔다. */
    private static BigDecimal percent(BigDecimal ratio) {
        return ratio == null ? BigDecimal.ZERO
                : ratio.multiply(BigDecimal.valueOf(100))
                        .setScale(0, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 닮은 구간 다음에 무슨 일이 있었는지와 서비스가 쓴 단서.
     *
     * <p>{@code /brief} 는 이것을 담지 않는다. {@code /similar} 를 부른 경우에만 있다.
     */
    private Optional<SimilarOutlook> toOutlook(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        JsonNode forward = node.path("forward_summary");
        String disclaimer = text(node, "disclaimer");
        if (forward.isMissingNode() && disclaimer.isBlank()) {
            return Optional.empty();
        }
        // 중앙 수익률(median_pct)도 함께 오지만 쓰지 않는다. 한 숫자로 요약하는 순간
        // "닮은 구간 다음에 이랬다" 가 "다음에 이렇게 된다" 로 읽힌다.
        return Optional.of(new SimilarOutlook(
                Math.max(0, forward.path("n").asInt(0)),
                Math.max(0, forward.path("up").asInt(0)),
                Math.max(0, forward.path("down").asInt(0)),
                text(forward, "note"),
                disclaimer));
    }

    /** 일부만 실패한 것을 조용히 빼지 않는다. 무엇이 빠졌는지 알려야 판단이 된다. */
    private Map<String, String> toFailures(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> failures = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> failures.put(entry.getKey(), entry.getValue().asText("")));
        return Map.copyOf(failures);
    }

    private static String text(JsonNode parent, String field) {
        return text(parent, field, "");
    }

    private static String text(JsonNode parent, String field, String fallback) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        String value = node.asText("");
        return value.isBlank() ? fallback : value;
    }

    private static BigDecimal decimal(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.decimalValue();
    }

    private static LocalDate date(JsonNode parent, String field) {
        String raw = text(parent, field);
        try {
            return raw.isBlank() ? null : LocalDate.parse(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
