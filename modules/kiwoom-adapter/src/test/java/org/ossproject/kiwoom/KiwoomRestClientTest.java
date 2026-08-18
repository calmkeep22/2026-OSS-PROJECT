package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.broker.BrokerAuthException;
import org.ossproject.broker.BrokerCredentials;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.BrokerRateLimitException;
import org.ossproject.broker.BrokerTransientException;
import org.ossproject.broker.resilience.CircuitBreaker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.broker.resilience.Sleeper;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiwoomRestClientTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-08T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String TOKEN_BODY =
            "{\"access_token\":\"tokenvalue123456\",\"expires_in\":86400}";

    /** 미리 준비한 응답을 순서대로 돌려주는 전송 스텁. */
    private static final class StubTransport implements HttpTransport {
        private final Deque<HttpTextResponse> responses = new ArrayDeque<>();
        private final List<HttpTextRequest> requests = new ArrayList<>();

        void enqueue(HttpTextResponse response) {
            responses.addLast(response);
        }

        void enqueueJson(String body) {
            enqueue(HttpTextResponse.of(200, body));
        }

        @Override
        public HttpTextResponse send(HttpTextRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                throw new IllegalStateException("준비된 응답이 없습니다. 요청 " + request.uri());
            }
            return responses.removeFirst();
        }
    }

    private StubTransport transport;
    private KiwoomProperties properties;
    private KiwoomRestClient client;
    private BrokerCredentials credentials;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        properties = KiwoomProperties.placeholder(
                URI.create("https://api.example.test"), URI.create("wss://api.example.test/ws"));
        credentials = BrokerCredentials.of("PS1a2b3c4d5e", "S9z8y7x6w5v4");

        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), properties, CLOCK);
        KiwoomTokenProvider tokenProvider =
                new KiwoomTokenProvider(transport, jsonMapper, properties, credentials, CLOCK);
        ResilientExecutor executor = new ResilientExecutor(
                RetryPolicy.immediate(3), CircuitBreaker.defaults(CLOCK), Sleeper.none());

        client = new KiwoomRestClient(transport, jsonMapper, properties, tokenProvider,
                credentials, executor);
    }

    @Test
    @DisplayName("토큰을 발급받고 이후 요청에 Bearer 헤더를 붙인다")
    void authenticatesAndAttachesToken() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"symbol\":\"005930\",\"price\":\"73500\",\"volume\":\"18450230\"}");

        Quote quote = client.fetchQuote("005930");

        assertEquals("005930", quote.symbol());
        assertEquals(0, new BigDecimal("73500").compareTo(quote.price()));
        assertEquals("Bearer tokenvalue123456",
                transport.requests.get(1).headers().get("authorization"));
    }

    @Test
    @DisplayName("발급받은 토큰을 재사용하고 매번 다시 요청하지 않는다")
    void reusesToken() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"symbol\":\"005930\",\"price\":\"73500\"}");
        transport.enqueueJson("{\"symbol\":\"005930\",\"price\":\"74000\"}");

        client.fetchQuote("005930");
        client.fetchQuote("005930");

        // 토큰 1회 + 시세 2회
        assertEquals(3, transport.requests.size());
        assertTrue(client.isAuthenticated());
    }

    @Test
    @DisplayName("401 을 받으면 토큰을 재발급받아 한 번 더 시도한다")
    void refreshesTokenOnUnauthorized() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(401, "{\"message\":\"expired\"}"));
        transport.enqueueJson("{\"access_token\":\"newtoken98765432\",\"expires_in\":86400}");
        transport.enqueueJson("{\"symbol\":\"005930\",\"price\":\"73500\"}");

        Quote quote = client.fetchQuote("005930");

        assertEquals(0, new BigDecimal("73500").compareTo(quote.price()));
        assertEquals("Bearer newtoken98765432",
                transport.requests.get(3).headers().get("authorization"));
    }

    @Test
    @DisplayName("재발급 후에도 401 이면 인증 오류를 던진다")
    void failsWhenStillUnauthorized() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(401, "{\"message\":\"invalid key\"}"));
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(401, "{\"message\":\"invalid key\"}"));

        assertThrows(BrokerAuthException.class, () -> client.fetchQuote("005930"));
    }

    @Test
    @DisplayName("5xx 응답은 재시도 대상 오류로 바꾼다")
    void mapsServerErrorToTransient() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(500, "internal error"));
        transport.enqueue(HttpTextResponse.of(500, "internal error"));
        transport.enqueue(HttpTextResponse.of(500, "internal error"));

        assertThrows(BrokerTransientException.class, () -> client.fetchQuote("005930"));
    }

    @Test
    @DisplayName("429 응답은 호출 한도 오류로 바꾸고 대기 시간을 읽는다")
    void mapsRateLimit() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(new HttpTextResponse(429, Map.of("Retry-After", "3"), "too many"));
        transport.enqueueJson("{\"symbol\":\"005930\",\"price\":\"73500\"}");

        Quote quote = client.fetchQuote("005930");

        assertEquals(0, new BigDecimal("73500").compareTo(quote.price()));
    }

    @Test
    @DisplayName("오류 메시지에 응답 속 토큰이 노출되지 않는다")
    void masksSecretsInErrorMessage() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(400,
                "{\"access_token\":\"leakedtoken123\",\"message\":\"bad request\"}"));

        BrokerException thrown = assertThrows(BrokerException.class, () -> client.fetchQuote("005930"));

        assertFalse(thrown.getMessage().contains("leakedtoken123"));
    }

    @Test
    @DisplayName("봉 응답을 오래된 것부터 시간순으로 정렬한다")
    void parsesCandlesInChronologicalOrder() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"candles":[
                  {"date":"20260807","open":"71000","high":"72000","low":"70500","close":"71800","volume":"9000"},
                  {"date":"20260805","open":"70000","high":"70800","low":"69500","close":"70500","volume":"8000"},
                  {"date":"20260806","open":"70500","high":"71500","low":"70000","close":"71000","volume":"8500"}
                ]}""");

        List<Candle> candles = client.fetchCandles("005930", CandleInterval.DAY, 3);

        assertEquals(3, candles.size());
        assertTrue(candles.get(0).timestamp().isBefore(candles.get(1).timestamp()));
        assertTrue(candles.get(1).timestamp().isBefore(candles.get(2).timestamp()));
        assertEquals(0, new BigDecimal("71800").compareTo(candles.get(2).close()));
    }

    @Test
    @DisplayName("잔고 응답을 계좌로 옮기고 수량 0인 종목은 제외한다")
    void parsesAccount() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"cash":"12500000","positions":[
                  {"symbol":"005930","name":"삼성전자","quantity":"20","average_price":"71000","current_price":"73500"},
                  {"symbol":"000660","name":"SK하이닉스","quantity":"0","average_price":"183000","current_price":"190500"}
                ]}""");

        Account account = client.fetchAccount("12345678901");

        assertEquals(1, account.positions().size());
        assertEquals("삼성전자", account.positions().get(0).name());
        assertEquals(0, new BigDecimal("12500000").compareTo(account.balance().cash()));
    }

    @Test
    @DisplayName("주문 내역의 부분 체결을 반영한다")
    void parsesPartiallyFilledOrder() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"orders":[{
                  "order_id":"K-1001","symbol":"005930","name":"삼성전자","side":"buy",
                  "order_type":"limit","quantity":"10","filled_quantity":"4",
                  "price":"70000","average_filled_price":"69800","status":"partially_filled"
                }]}""");

        List<Order> orders = client.fetchOrders("12345678901");

        assertEquals(1, orders.size());
        Order order = orders.get(0);
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
        assertEquals(4L, order.filledQuantity());
        assertEquals(6L, order.remainingQuantity());
    }

    @Test
    @DisplayName("알 수 없는 상태 코드는 조용히 넘어가지 않고 오류를 낸다")
    void rejectsUnknownStatusCode() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"orders":[{
                  "order_id":"K-1001","symbol":"005930","name":"삼성전자","side":"buy",
                  "order_type":"limit","quantity":"10","price":"70000","status":"???"
                }]}""");

        BrokerException thrown = assertThrows(BrokerException.class,
                () -> client.fetchOrders("12345678901"));

        assertTrue(thrown.getMessage().contains("알 수 없는 주문 상태"));
    }

    @Test
    @DisplayName("필수 항목이 없으면 오류를 낸다")
    void rejectsMissingField() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"symbol\":\"005930\"}");

        BrokerException thrown = assertThrows(BrokerException.class, () -> client.fetchQuote("005930"));

        assertTrue(thrown.getMessage().contains("price"));
    }

    @Test
    @DisplayName("주문을 접수하면 증권사 주문 번호를 돌려준다")
    void placesOrder() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"order_id\":\"K-2001\"}");

        String orderId = client.placeOrder("12345678901",
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 10, new BigDecimal("70000")));

        assertEquals("K-2001", orderId);
        String body = transport.requests.get(1).body();
        assertTrue(body.contains("\"side\":\"buy\""));
        assertTrue(body.contains("\"quantity\":10"));
        assertTrue(body.contains("\"price\":70000"));
    }

    @Test
    @DisplayName("시장가 주문 본문에는 가격을 넣지 않는다")
    void placesMarketOrderWithoutPrice() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"order_id\":\"K-2002\"}");

        client.placeOrder("12345678901",
                OrderCommand.market("005930", "삼성전자", OrderSide.BUY, 10));

        String body = transport.requests.get(1).body();
        assertTrue(body.contains("\"orderType\":\"market\""));
        assertFalse(body.contains("\"price\""));
    }

    @Test
    @DisplayName("실거래 설정에서 실행 인자가 없으면 요청을 보내지 않는다")
    void blocksLiveTradingWithoutExplicitFlag() {
        KiwoomProperties live = new KiwoomProperties(
                properties.restBaseUrl(), properties.webSocketUrl(), properties.endpoints(),
                properties.fields(), properties.orderStatusCodes(), properties.orderSideCodes(),
                properties.orderTypeCodes(), properties.requestTimeout(), false);

        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), live, CLOCK);
        KiwoomRestClient liveClient = new KiwoomRestClient(transport, jsonMapper, live,
                new KiwoomTokenProvider(transport, jsonMapper, live, credentials, CLOCK),
                credentials,
                new ResilientExecutor(RetryPolicy.none(), CircuitBreaker.defaults(CLOCK), Sleeper.none()));

        assertThrows(BrokerAuthException.class, () -> liveClient.fetchAccount("12345678901"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    @DisplayName("토큰 만료 시각이 지나면 다시 발급받는다")
    void reissuesExpiredToken() {
        TestClock clock = new TestClock(Instant.parse("2026-08-08T01:00:00Z"));
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), properties, clock);
        KiwoomTokenProvider provider =
                new KiwoomTokenProvider(transport, jsonMapper, properties, credentials, clock);

        transport.enqueueJson("{\"access_token\":\"first12345678\",\"expires_in\":600}");
        assertEquals("first12345678", provider.token().value());

        clock.advance(Duration.ofMinutes(10));
        transport.enqueueJson("{\"access_token\":\"second1234567\",\"expires_in\":600}");
        assertEquals("second1234567", provider.token().value());
    }
}
