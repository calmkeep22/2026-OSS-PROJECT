package org.ossproject.ai.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.Forecast;
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

    private static final String SIMILAR = """
            {"종목코드":"005930","종목명":"삼성전자","후보종목수":299,
             "results":[
               {"rank":1,"code":"000660","name":"SK하이닉스","end":"2024-03-15",
                "similarity":0.932,"동조도":0.21},
               {"rank":2,"code":"035420","name":"NAVER","end":"2023-11-02",
                "similarity":0.881,"동조도":0.64},
               {"rank":3,"code":"051910","name":"LG화학","end":"2023-06-01",
                "similarity":0.870,"동조도":0.33},
               {"rank":4,"code":"005380","name":"현대차","end":"2023-02-14",
                "similarity":0.862,"동조도":0.41},
               {"rank":5,"code":"068270","name":"셀트리온","end":"2022-12-05",
                "similarity":0.855,"동조도":0.19}],
             "forward_summary":{"n":5,"up":3,"down":2,"median_pct":3.91,
                                "note":"표본이 적고 미래를 보장하지 않습니다."},
             "disclaimer":"이건 예측이 아닙니다. 닮은 구간 다음 일을 세었을 뿐입니다."}
            """;

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final List<String> paths = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/brief", exchange -> respond(exchange, 200, BRIEF));
        server.createContext("/predict", exchange -> respond(exchange, 200, PREDICT));
        server.createContext("/similar", exchange -> respond(exchange, 200, SIMILAR));
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

    /**
     * brief 의 유사종목 요약에는 함께 움직인 정도가 없다. 모양이 0.93 으로 닮았는데
     * 동조가 0.21 인 짝이 흔하다 — 다른 시기의 다른 종목이 우연히 같은 곡선을 그린 경우다.
     */
    @Test
    @DisplayName("닮은 종목은 /similar 원본에서 함께 움직인 정도까지 받는다")
    void fetchesTheFullSimilarPayload() {
        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertEquals(3, insight.similar().size());
        assertEquals(new BigDecimal("21"),
                insight.similar().get(0).comovementPercent().orElseThrow());
        assertTrue(bodies.containsKey("/similar"));
    }

    /** 유사도 기능이 사실로 말할 수 있는 거의 전부다. 요약에는 빠져 있다. */
    @Test
    @DisplayName("닮은 구간 다음의 상승·하락 건수와 서비스 단서를 받는다")
    void fetchesTheForwardCountsAndDisclaimer() {
        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertEquals(3, insight.similarOutlook().orElseThrow().up());
        assertEquals(2, insight.similarOutlook().orElseThrow().down());
        assertTrue(insight.requiredCaveats().stream()
                .anyMatch(c -> c.startsWith("이건 예측이 아닙니다")),
                insight.requiredCaveats().toString());
    }

    /** 상세가 아니면 부를 이유가 없다. 목록 화면은 종목당 한 줄이다. */
    @Test
    @DisplayName("닮은 차트를 안 물었으면 /similar 를 부르지 않는다")
    void skipsTheSimilarCallForListScreens() {
        adapter().brief(SecurityId.of("005930", "KRX"), bars(), false);

        assertFalse(bodies.containsKey("/similar"));
    }

    /** 유사도 하나 때문에 예측과 이상감지까지 잃을 이유가 없다. */
    @Test
    @DisplayName("/similar 가 실패하면 brief 요약으로 되돌아간다")
    void fallsBackToTheBriefSummary() {
        server.removeContext("/similar");
        server.createContext("/similar", exchange -> respond(exchange, 500, "{}"));

        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertEquals(1, insight.similar().size());
        assertEquals("SK하이닉스", insight.similar().get(0).name());
        assertTrue(insight.similar().get(0).comovementPercent().isEmpty());
        assertTrue(insight.similarOutlook().isEmpty());
    }

    /**
     * 다섯 개면 종목명과 숫자 둘씩 열 개를 연달아 듣게 되어 앞의 것을 기억하지 못한 채
     * 끝난다. 화면을 볼 수 없는 사용자에게는 이 문장이 목록의 전부다.
     */
    @Test
    @DisplayName("닮은 종목은 셋까지만 읽어 준다")
    void capsTheSimilarListAtThree() {
        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertEquals(3, insight.similar().size());
        assertEquals("SK하이닉스", insight.similar().get(0).name());
        assertEquals("LG화학", insight.similar().get(2).name());
    }

    /**
     * 확률 이름이 타깃에 따라 바뀐다. 아무거나 집으면 "하락" 판정에 상승확률 49.3
     * 퍼센트가 붙어 판정과 숫자가 서로 반대를 가리킨다. 화면을 볼 수 없는 사용자는 그
     * 모순을 확인할 방법이 없다.
     */
    @Test
    @DisplayName("판정이 하락이면 하락확률을 읽어 준다")
    void pairsTheProbabilityWithTheVerdict() {
        server.removeContext("/predict");
        server.createContext("/predict", exchange -> respond(exchange, 200, """
                {"타깃":"방향","예측":"하락","신뢰도":"보통","유의미":false,
                 "상승확률":49.3,"하락확률":50.7,"대상일":"2026-08-24","금일여부":false}
                """));

        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        Forecast direction = insight.directionForecast().orElseThrow();
        assertEquals("하락", direction.verdict());
        assertEquals(new BigDecimal("50.7"), direction.probability());
    }

    /** 판정과 반대쪽 확률을 보여 주는 것은 아무것도 안 보여 주는 것보다 나쁘다. */
    @Test
    @DisplayName("판정에 맞는 확률이 없으면 그 예측을 접는다")
    void dropsTheForecastWhenNoProbabilityMatches() {
        server.removeContext("/predict");
        server.createContext("/predict", exchange -> respond(exchange, 200, """
                {"타깃":"방향","예측":"보합","신뢰도":"보통","유의미":false,
                 "상승확률":49.3,"하락확률":50.7,"대상일":"2026-08-24","금일여부":false}
                """));

        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertTrue(insight.directionForecast().isEmpty());
        // 문안은 그대로 나간다. 확률 하나 때문에 종목 요약을 잃지 않는다.
        assertTrue(insight.narration().contains("이례적"));
    }

    @Test
    @DisplayName("검증되지 않은 방향 예측에는 그 사실을 붙여 읽어 준다")
    void warnsThatTheDirectionIsNotMeaningful() {
        AiInsight insight = adapter().brief(SecurityId.of("005930", "KRX"), bars(), true);

        assertTrue(insight.fullNarration().contains("오를지 내릴지"), insight.fullNarration());
    }
}
