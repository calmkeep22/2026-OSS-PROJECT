package org.ossproject.ai.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.ai.AiUnavailableException;
import org.ossproject.ai.MarketIndex;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 지수·환율을 받아 오는 창구.
 *
 * <p>여기서 지키려는 것은 하나다 — <b>받지 못한 것을 받은 것처럼 만들지 않는다.</b>
 * 홈 화면 맨 위에 있어서 앱을 열면 가장 먼저 듣는 값이고, 화면을 볼 수 없는 사용자는
 * 그것이 실제 시세인지 채워 넣은 값인지 구별할 방법이 없다.
 */
class HttpMarketOverviewAdapterTest {

    private HttpServer server;
    private String body = "{}";
    private int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/market/overview", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private List<MarketIndex> fetch() {
        return new HttpMarketOverviewAdapter(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())).overview();
    }

    @Test
    @DisplayName("보내 준 순서 그대로 읽는다")
    void readsIndicesInOrder() {
        body = """
                {"indices":[
                  {"code":"KS11","name":"코스피","group":"국내지수",
                   "value":6696.96,"change_percent":-3.12,"as_of":"2026-08-24"},
                  {"code":"US500","name":"S&P 500","group":"해외지수",
                   "value":7674.37,"change_percent":0.43,"as_of":"2026-08-21"}]}""";

        List<MarketIndex> indices = fetch();

        assertEquals(2, indices.size());
        MarketIndex kospi = indices.get(0);
        assertEquals("코스피", kospi.name());
        assertEquals("국내지수", kospi.group());
        assertEquals(0, kospi.value().compareTo(new java.math.BigDecimal("6696.96")));
        assertEquals(LocalDate.of(2026, 8, 24), kospi.asOf());
        assertEquals("하락", kospi.direction());
        assertEquals("해외지수", indices.get(1).group());
    }

    /**
     * 서비스는 못 받은 지표를 빼고 준다. 하나가 빠졌다고 나머지까지 버리면, 코스피는
     * 멀쩡한데 해외 서버가 느리다는 이유로 화면이 통째로 빈다.
     */
    @Test
    @DisplayName("일부만 와도 온 것은 준다")
    void keepsWhatArrived() {
        body = """
                {"indices":[{"code":"KS11","name":"코스피","group":"국내지수",
                 "value":6696.96,"change_percent":-3.12,"as_of":"2026-08-24"}]}""";

        assertEquals(1, fetch().size());
    }

    @Test
    @DisplayName("아무것도 못 받았으면 빈 목록이다")
    void emptyWhenNothingArrived() {
        body = "{\"indices\":[]}";

        assertTrue(fetch().isEmpty());
    }

    /**
     * 값이나 기준일이 없는 항목은 버린다. 날짜 없는 숫자를 화면에 올리면 사용자는
     * 그것을 오늘 값으로 읽는데, 미국장은 주말 내내 금요일 종가 그대로다.
     */
    @Test
    @DisplayName("기준일 없는 항목은 버린다")
    void dropsItemsWithoutDate() {
        body = """
                {"indices":[{"code":"KS11","name":"코스피","group":"국내지수",
                 "value":6696.96,"change_percent":-3.12}]}""";

        assertTrue(fetch().isEmpty(), "날짜 없는 시세는 오늘 값으로 읽힌다");
    }

    @Test
    @DisplayName("값 없는 항목은 버린다")
    void dropsItemsWithoutValue() {
        body = """
                {"indices":[{"code":"KS11","name":"코스피","group":"국내지수",
                 "change_percent":-3.12,"as_of":"2026-08-24"}]}""";

        assertTrue(fetch().isEmpty());
    }

    /**
     * 등락을 못 받은 것과 안 움직인 것은 다르다. 0 으로 채우면 "보합" 으로 읽힌다.
     */
    @Test
    @DisplayName("등락이 없으면 0 이 아니라 비어 있다")
    void missingChangeIsNotZero() {
        body = """
                {"indices":[{"code":"USD/KRW","name":"원/달러","group":"환율",
                 "value":1382.58,"as_of":"2026-08-24"}]}""";

        MarketIndex fx = fetch().get(0);

        assertTrue(fx.changePercent().isEmpty());
        assertEquals("", fx.direction(), "모르는 것을 보합이라 하지 않는다");
        assertEquals("등락 미상", fx.changeText());
    }

    @Test
    @DisplayName("서버가 없으면 조용히 빈 목록을 주지 않는다")
    void failsLoudlyWhenServiceIsDown() {
        server.stop(0);

        AiUnavailableException failure = assertThrows(AiUnavailableException.class, this::fetch);

        assertNotNull(failure.getMessage());
        assertFalse(failure.getMessage().isBlank(),
                "사용자가 무엇을 해야 할지 알 수 있는 말이어야 한다");
    }

    @Test
    @DisplayName("서버가 오류를 내면 그 사실을 알린다")
    void reportsServerError() {
        status = 500;
        body = "{}";

        assertThrows(AiUnavailableException.class, this::fetch);
    }
}
