package org.ossproject.ai.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.ai.AiInsight;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecurityId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 방향 예측은 {@code /brief} 가 담지 않아 {@code /predict} 를 한 번 더 부른다.
 *
 * <p>가짜 서버를 세워 확인한다. 어댑터를 흉내 내는 대신 진짜 HTTP 를 태워야 요청 본문과
 * 상태 코드 처리가 함께 검증된다. 그 둘이 이 클래스가 하는 일의 전부다.
 */
class HttpAiInsightAdapterTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 마감 20분 뒤. 오늘 봉이 확정으로 취급되는 시각이라 봉이 걸러지지 않는다. */
    private static final Clock AFTER_CLOSE = Clock.fixed(
            LocalDate.of(2026, 8, 21).atTime(16, 0).atZone(SEOUL).toInstant(), SEOUL);

    private static final String BRIEF = """
            {"종목코드":"005930","종목명":"삼성전자",
             "문안":"삼성전자는 오늘 이례적으로 하락했습니다.",
             "예측":{"타깃":"변동성","예측":"크게움직임","신뢰도":"높음","유의미":true,
                     "크게움직임확률":61.2,"대상일":"2026-08-24","금일여부":false},
             "이상감지":{"이상":true,"등급":"강함","방향":"하락","관측일":"2026-08-21",
                         "변동률":-4.2,"위험도":"상위 12퍼센트","조언":"나누어 담으세요."},
             "유사종목":[{"종목코드":"000660","종목명":"SK하이닉스","유사도":0.88}],
             "오류":{}}
            """;

    private static final String PREDICT = """
            {"타깃":"방향","예측":"상승","신뢰도":"보통","유의미":false,
             "상승확률":52.9,"대상일":"2026-08-24","금일여부":false}
            """;

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/brief", exchange -> respond(exchange, 200, BRIEF));
        server.createContext("/predict", exchange -> respond(exchange, 200, PREDICT));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        synchronized (paths) {
            paths.add(exchange.getRequestURI().getPath());
        }
        try (InputStream in = exchange.getRequestBody()) {
            bodies.put(exchange.getRequestURI().getPath(),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpAiInsightAdapter adapter() {
        return new HttpAiInsightAdapter(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), AFTER_CLOSE);
    }

    private static List<Candle> bars() {
        List<Candle> bars = new ArrayList<>();
        for (int i = 300; i > 0; i--) {
            LocalDate date = LocalDate.of(2026, 8, 21).minusDays(i);
            bars.add(new Candle(date.atTime(9, 0).atZone(SEOUL).toInstant(), CandleInterval.DAY,
                    new BigDecimal("100"), new BigDecimal("110"),
                    new BigDecimal("90"), new BigDecimal("105"), 1000L));
        }
        return List.copyOf(bars);
    }

    @Test
    @DisplayName("변동성과 방향을 둘 다 받아 한 결과로 합친다")
    void fetchesBothForecasts() {
        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertEquals("크게움직임", insight.forecast().orElseThrow().verdict());
        assertEquals("상승", insight.directionForecast().orElseThrow().verdict());
        assertEquals(new BigDecimal("52.9"), insight.directionForecast().orElseThrow().probability());
        synchronized (paths) {
            assertTrue(paths.contains("/brief") && paths.contains("/predict"), paths.toString());
        }
    }

    /** 타깃을 안 보내면 서비스는 변동성으로 답한다. 그러면 방향 자리에 변동성이 들어간다. */
    @Test
    @DisplayName("방향을 물을 때는 타깃을 방향으로 보낸다")
    void asksForTheDirectionTarget() {
        adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertTrue(bodies.get("/predict").contains("\"target\":\"방향\""), bodies.get("/predict"));
        assertTrue(bodies.get("/brief").contains("\"target\":\"변동성\""), bodies.get("/brief"));
    }

    /**
     * 방향은 곁들이는 값이다. 그것 하나 때문에 오늘 이례적으로 빠졌다는 사실까지 사라지면
     * 사용자는 더 중요한 것을 잃는다.
     */
    @Test
    @DisplayName("방향 예측이 실패해도 나머지 분석은 그대로 준다")
    void keepsTheBriefWhenTheDirectionCallFails() {
        server.removeContext("/predict");
        server.createContext("/predict", exchange -> respond(exchange, 500, "{}"));

        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertTrue(insight.directionForecast().isEmpty());
        assertEquals("크게움직임", insight.forecast().orElseThrow().verdict());
        assertTrue(insight.narration().contains("이례적"));
    }

    @Test
    @DisplayName("검증되지 않은 방향 예측에는 그 사실을 붙여 읽어 준다")
    void warnsThatTheDirectionIsNotMeaningful() {
        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertTrue(insight.fullNarration().contains("오를지 내릴지"), insight.fullNarration());
    }
}
