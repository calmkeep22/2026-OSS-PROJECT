package org.ossproject.ai.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.ai.NewsDigest;
import org.ossproject.finance.model.SecurityId;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 뉴스 창구는 남의 서버(RSS)를 거친다. 분석이 멀쩡해도 혼자 실패하고, 기사가 없는 것과
 * 조회에 실패한 것이 다르다. 그 구분이 화면까지 살아 오는지 본다.
 */
class HttpNewsAdapterTest {

    private static final String NEWS = """
            {"종목코드":"005930","종목명":"A전자","지수":12.4,"지수해석":"약간 긍정",
             "기사수":9,"사건수":3,"긍정":4,"중립":3,"부정":2,
             "사건":["A전자가 신규 시설 투자를 공시했습니다.","반도체 업종 거래량이 늘었습니다."],
             "시황":"오늘 시황 보도입니다. 지수가 강보합입니다.",
             "기사":[{"제목":"A전자, 신규 시설 투자 계획 공시","출처":"경제 신문",
                      "시각":"2026-08-22T11:02:00+09:00","주소":"https://example.test/1",
                      "감성":"positive"},
                     {"제목":"반도체 업종 거래량 확대","출처":"거래소 공시",
                      "시각":"2026-08-22 10:35:00+09:00","주소":"https://example.test/2",
                      "감성":"neutral"}],
             "브리핑":"A전자 뉴스 브리핑입니다. 뉴스 감성 지수는 여론의 방향을 요약한 것이며 주가 예측이 아닙니다."}
            """;

    private static final String EMPTY_NEWS = """
            {"종목코드":"005930","종목명":"A전자","기사":[],"사건":[],"지수":null,
             "브리핑":"관련 뉴스를 찾지 못했습니다."}
            """;

    private static final String CHAT = """
            {"답변":"단정할 수 없습니다. 투자 규모와 재무 상태를 함께 확인하세요.",
             "근거":["뉴스 감성 분석"],"거절":false,
             "추천질문":["핵심 수치 알려줘","쉽게 설명해줘"]}
            """;

    private HttpServer server;
    private final Map<String, String> bodies = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/news", exchange -> respond(exchange, 200, NEWS));
        server.createContext("/news/track", exchange -> respond(exchange, 200, "{\"추가\":1}"));
        server.createContext("/chat", exchange -> respond(exchange, 200, CHAT));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
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

    private HttpNewsAdapter adapter() {
        return new HttpNewsAdapter(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static SecurityId samsung() {
        return SecurityId.of("005930", "KRX");
    }

    @Test
    @DisplayName("기사와 사건과 감성 지수를 함께 받는다")
    void readsArticlesEventsAndScore() {
        NewsDigest digest = adapter().news(samsung());

        assertEquals(2, digest.articles().size());
        assertEquals("경제 신문", digest.articles().get(0).source());
        assertEquals(2, digest.events().size());
        assertEquals(12.4, digest.sentimentScore().orElseThrow(), 0.001);
        assertTrue(digest.marketLine().isPresent());
    }

    /** 시각 표기가 한 가지가 아니다. 오프셋을 붙인 것과 공백으로 나눈 것이 섞여 온다. */
    @Test
    @DisplayName("표기가 다른 시각도 읽는다")
    void parsesBothTimestampShapes() {
        NewsDigest digest = adapter().news(samsung());

        assertNotNull(digest.articles().get(0).publishedAt());
        assertNotNull(digest.articles().get(1).publishedAt());
    }

    /**
     * 점수만 말하면 12점이 좋은 것인지 나쁜 것인지, 무엇에 대한 점수인지 알 수 없다.
     * 그리고 지수는 예측이 아니라는 사실이 반드시 함께 가야 한다.
     */
    @Test
    @DisplayName("감성 지수는 예측이 아니라는 사실과 함께 읽어 준다")
    void alwaysSaysTheScoreIsNotAForecast() {
        String text = adapter().news(samsung()).sentimentText().orElseThrow();

        assertTrue(text.contains("약간 긍정"), text);
        assertTrue(text.contains("주가 예측이 아닙니다"), text);
    }

    /** 기사가 없는 것과 조회에 실패한 것은 다르다. 빈 목록으로 뭉개면 구별되지 않는다. */
    @Test
    @DisplayName("기사가 없으면 없다고 말한다")
    void saysSoWhenThereIsNoNews() {
        server.removeContext("/news");
        server.createContext("/news", exchange -> respond(exchange, 200, EMPTY_NEWS));

        NewsDigest digest = adapter().news(samsung());

        assertTrue(digest.isEmpty());
        assertEquals("관련 뉴스를 찾지 못했습니다.", digest.briefing());
        assertTrue(digest.sentimentText().isEmpty());
    }

    @Test
    @DisplayName("조회에 실패하면 빈 결과로 뭉개지 않고 알린다")
    void failsLoudlyWhenTheServiceIsDown() {
        server.removeContext("/news");
        server.createContext("/news", exchange -> respond(exchange, 500, "{}"));

        assertThrows(AiUnavailableException.class, () -> adapter().news(samsung()));
    }

    @Test
    @DisplayName("질문과 답과 근거를 그대로 오간다")
    void carriesTheQuestionAndAnswer() {
        ChatAnswer answer = adapter().ask(samsung(), "이 공시가 주가 상승을 뜻해?", null);

        assertFalse(answer.declined());
        assertTrue(answer.text().startsWith("단정할 수 없습니다"));
        assertEquals("근거: 뉴스 감성 분석.", answer.groundsText());
        assertEquals(2, answer.suggestions().size());
        assertTrue(bodies.get("/chat").contains("이 공시가 주가 상승을 뜻해?"));
    }

    /**
     * 사용자가 친 글자가 그대로 본문에 들어간다. 따옴표나 줄바꿈을 손으로 붙이면 본문이
     * 깨지고, 서버는 "본문을 읽지 못했습니다" 로만 답해 원인을 찾기 어렵다.
     */
    @Test
    @DisplayName("따옴표와 줄바꿈이 든 질문도 깨지지 않는다")
    void survivesQuotesAndNewlinesInTheQuestion() {
        adapter().ask(samsung(), "\"공시\"가\n무슨 뜻이야?", null);

        assertTrue(bodies.get("/chat").contains("\\\"공시\\\""), bodies.get("/chat"));
        assertTrue(bodies.get("/chat").contains("\\n"), bodies.get("/chat"));
    }

    @Test
    @DisplayName("빈 질문은 서버까지 보내지 않는다")
    void rejectsAnEmptyQuestion() {
        assertThrows(IllegalArgumentException.class, () -> adapter().ask(samsung(), "  ", null));
    }

    /** 미리받기는 사용자가 기다리는 일이 아니다. 실패해도 화면이 멈출 이유가 없다. */
    @Test
    @DisplayName("미리받기가 실패해도 예외를 올리지 않는다")
    void neverThrowsFromTracking() {
        server.removeContext("/news/track");
        server.createContext("/news/track", exchange -> respond(exchange, 500, "{}"));

        assertDoesNotThrow(() -> adapter().track(List.of(samsung())));
    }

    @Test
    @DisplayName("미리받기는 종목 코드를 모아 한 번에 보낸다")
    void tracksEveryCodeInOneCall() {
        adapter().track(List.of(samsung(), SecurityId.of("035720", "KRX")));

        assertTrue(bodies.get("/news/track").contains("005930"));
        assertTrue(bodies.get("/news/track").contains("035720"));
    }
}
