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
import org.ossproject.finance.model.StockDetail;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
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

/**
 * 응답 예시는 키움 REST API 공식 문서의 Response Example 을 그대로 옮긴 것이다.
 * 값 표기(부호 접두, 0-패딩, 종목코드 접두어)를 실제와 같게 유지해야 회귀를 잡을 수 있다.
 */
class KiwoomRestClientTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-08T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String TOKEN_BODY = """
            {"expires_dt":"20260810083713","token_type":"bearer",
             "token":"tokenvalue123456","return_code":0,"return_msg":"정상적으로 처리되었습니다"}""";

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

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        properties = KiwoomProperties.mockTrading(URI.create("wss://mockapi.kiwoom.com:10000/api/dostk/websocket"));
        client = newClient(properties, RetryPolicy.immediate(3));
    }

    private KiwoomRestClient newClient(KiwoomProperties config, RetryPolicy retryPolicy) {
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), CLOCK);
        BrokerCredentials credentials = BrokerCredentials.of("PS1a2b3c4d5e", "S9z8y7x6w5v4");
        KiwoomTokenProvider tokenProvider =
                new KiwoomTokenProvider(transport, jsonMapper, config, credentials, CLOCK);
        return new KiwoomRestClient(transport, jsonMapper, config, tokenProvider,
                new ResilientExecutor(retryPolicy, CircuitBreaker.defaults(CLOCK), Sleeper.none()));
    }

    // ------------------------------------------------------------------
    // 인증
    // ------------------------------------------------------------------

    @Test
    @DisplayName("토큰 요청은 공식 스펙대로 secretkey 를 보내고 token 을 읽는다")
    void issuesTokenWithOfficialFieldNames() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson(basicInfo());

        client.fetchQuote("005930");

        String tokenBody = transport.requests.get(0).body();
        assertTrue(tokenBody.contains("\"secretkey\""), "요청 본문은 secretkey 를 써야 합니다");
        assertFalse(tokenBody.contains("appsecret"), "appsecret 은 공식 스펙에 없습니다");
        assertTrue(tokenBody.contains("\"grant_type\":\"client_credentials\""));
        assertEquals("Bearer tokenvalue123456",
                transport.requests.get(1).headers().get("authorization"));
    }

    @Test
    @DisplayName("모의투자 도메인과 TR 경로로 요청한다")
    void usesMockHostAndTrPath() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson(basicInfo());

        client.fetchQuote("005930");

        assertEquals("https://mockapi.kiwoom.com/oauth2/token",
                transport.requests.get(0).uri().toString());
        assertEquals("https://mockapi.kiwoom.com/api/dostk/stkinfo",
                transport.requests.get(1).uri().toString());
        assertEquals("ka10001", transport.requests.get(1).headers().get("api-id"));
        assertEquals("POST", transport.requests.get(1).method());
    }

    @Test
    @DisplayName("401 을 받으면 토큰을 재발급받아 한 번 더 시도한다")
    void refreshesTokenOnUnauthorized() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(401, "{\"return_code\":8005}"));
        transport.enqueueJson("""
                {"expires_dt":"20260810083713","token_type":"bearer",
                 "token":"newtoken98765432","return_code":0}""");
        transport.enqueueJson(basicInfo());

        Quote quote = client.fetchQuote("005930");

        assertEquals(0, new BigDecimal("70700").compareTo(quote.price()));
        assertEquals("Bearer newtoken98765432",
                transport.requests.get(3).headers().get("authorization"));
    }

    // ------------------------------------------------------------------
    // 본문 오류 코드
    // ------------------------------------------------------------------

    @Test
    @DisplayName("HTTP 200 이라도 return_code 가 0이 아니면 실패로 처리한다")
    void treatsNonZeroReturnCodeAsFailure() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"return_code":1902,"return_msg":"종목 정보가 없습니다. 입력한 종목코드 값을 확인바랍니다."}""");

        BrokerException thrown = assertThrows(BrokerException.class, () -> client.fetchQuote("999999"));

        assertTrue(thrown.getMessage().contains("1902"));
        assertTrue(thrown.getMessage().contains("종목 정보가 없습니다"));
    }

    @Test
    @DisplayName("자격증명 오류 코드는 인증 예외로 바꾼다")
    void mapsCredentialErrorCodeToAuthFailure() {
        transport.enqueueJson("""
                {"return_code":8020,"return_msg":"입력파라미터로 appkey 또는 secretkey가 들어오지 않았습니다."}""");

        assertThrows(BrokerAuthException.class, () -> client.fetchQuote("005930"));
    }

    @Test
    @DisplayName("호출 한도 코드는 재시도 가능한 예외로 바꾼다")
    void mapsQuotaCodeToRateLimit() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"return_code":1700,"return_msg":"허용된 API 요청 개수를 초과하였습니다."}""");
        transport.enqueueJson(basicInfo());

        // 재시도 정책이 한 번 더 시도해 성공한다.
        Quote quote = client.fetchQuote("005930");
        assertEquals(0, new BigDecimal("70700").compareTo(quote.price()));
    }

    @Test
    @DisplayName("5xx 응답은 재시도 대상 오류로 바꾼다")
    void mapsServerErrorToTransient() {
        KiwoomRestClient noRetry = newClient(properties, RetryPolicy.none());
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(500, "internal error"));

        assertThrows(BrokerTransientException.class, () -> noRetry.fetchQuote("005930"));
    }

    @Test
    @DisplayName("429 응답은 대기 시간을 담은 호출 한도 오류로 바꾼다")
    void mapsRateLimitStatus() {
        HttpTextResponse response =
                new HttpTextResponse(429, Map.of("Retry-After", "3"), "too many");

        BrokerException mapped = KiwoomErrorMapper.toException("현재가 조회", response);

        assertTrue(mapped instanceof BrokerRateLimitException, "429 는 호출 한도 오류여야 합니다");
        assertEquals(java.util.Optional.of(java.time.Duration.ofSeconds(3)),
                ((BrokerRateLimitException) mapped).retryAfter());
        assertTrue(mapped.isRetryable());
    }

    @Test
    @DisplayName("한도 초과가 재시도로 회복되지 않으면 재시도 가능 오류로 보고한다")
    void reportsExhaustedRateLimitAsRetryable() {
        transport.enqueueJson(TOKEN_BODY);
        for (int attempt = 0; attempt < 5; attempt++) {
            transport.enqueue(new HttpTextResponse(429, Map.of("Retry-After", "1"), "too many"));
        }

        BrokerException thrown = assertThrows(BrokerException.class, () -> client.fetchQuote("005930"));

        // 재시도를 모두 쓰면 실행기가 재시도 가능 오류로 감싼다.
        assertTrue(thrown.isRetryable());
    }

    @Test
    @DisplayName("오류 메시지에 응답 속 토큰이 노출되지 않는다")
    void masksSecretsInErrorMessage() {
        KiwoomRestClient noRetry = newClient(properties, RetryPolicy.none());
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(400,
                "{\"token\":\"leakedtoken123\",\"return_msg\":\"bad request\"}"));

        BrokerException thrown = assertThrows(BrokerException.class, () -> noRetry.fetchQuote("005930"));

        assertFalse(thrown.getMessage().contains("leakedtoken123"));
    }

    // ------------------------------------------------------------------
    // 시세
    // ------------------------------------------------------------------

    @Test
    @DisplayName("부호가 붙은 숫자를 그대로 해석한다")
    void parsesSignedNumbers() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson(basicInfo());

        StockDetail detail = client.fetchStockDetail("005930");

        assertEquals("005930", detail.symbol());
        assertEquals("삼성전자", detail.name());
        assertEquals(0, new BigDecimal("70700").compareTo(detail.currentPrice()));
        assertEquals(0, new BigDecimal("69800").compareTo(detail.open()));
        assertEquals(18_450_230L, detail.volume());
    }

    // ------------------------------------------------------------------
    // 봉
    // ------------------------------------------------------------------

    @Test
    @DisplayName("일봉을 오래된 것부터 정렬하고 cur_prc 를 종가로 읽는다")
    void parsesDailyCandlesOldestFirst() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"stk_cd":"005930","stk_dt_pole_chart_qry":[
                  {"cur_prc":"70100","trde_qty":"9263135","dt":"20250908",
                   "open_pric":"69800","high_pric":"70500","low_pric":"69600","pred_pre":"+600"},
                  {"cur_prc":"69500","trde_qty":"11526724","dt":"20250905",
                   "open_pric":"70300","high_pric":"70400","low_pric":"69500","pred_pre":"-600"}
                ],"return_code":0}""");

        List<Candle> candles = client.fetchCandles("005930", CandleInterval.DAY, 10);

        assertEquals(2, candles.size());
        assertTrue(candles.get(0).timestamp().isBefore(candles.get(1).timestamp()));
        assertEquals(0, new BigDecimal("69500").compareTo(candles.get(0).close()));
        assertEquals(0, new BigDecimal("70100").compareTo(candles.get(1).close()));
        assertEquals("ka10081", transport.requests.get(1).headers().get("api-id"));
    }

    @Test
    @DisplayName("분봉은 tic_scope 를 보내고 cntr_tm 을 시각으로 읽는다")
    void parsesMinuteCandles() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"stk_cd":"005930","stk_min_pole_chart_qry":[
                  {"cur_prc":"70100","trde_qty":"1000","cntr_tm":"20250908093000",
                   "open_pric":"70000","high_pric":"70200","low_pric":"69900"}
                ],"return_code":0}""");

        List<Candle> candles = client.fetchCandles("005930", CandleInterval.MINUTE_5, 10);

        assertEquals(1, candles.size());
        assertEquals("ka10080", transport.requests.get(1).headers().get("api-id"));
        assertTrue(transport.requests.get(1).body().contains("\"tic_scope\":\"5\""));
        assertEquals(Instant.parse("2025-09-08T00:30:00Z"), candles.get(0).timestamp());
    }

    @Test
    @DisplayName("요청한 개수보다 많이 오면 최신 구간만 남긴다")
    void limitsCandlesToRequestedCount() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"stk_dt_pole_chart_qry":[
                  {"cur_prc":"3","dt":"20250903"},
                  {"cur_prc":"2","dt":"20250902"},
                  {"cur_prc":"1","dt":"20250901"}
                ],"return_code":0}""");

        List<Candle> candles = client.fetchCandles("005930", CandleInterval.DAY, 2);

        assertEquals(2, candles.size());
        assertEquals(0, new BigDecimal("2").compareTo(candles.get(0).close()));
        assertEquals(0, new BigDecimal("3").compareTo(candles.get(1).close()));
    }

    // ------------------------------------------------------------------
    // 계좌
    // ------------------------------------------------------------------

    @Test
    @DisplayName("예수금과 잔고를 합치고 0-패딩과 종목코드 접두어를 걷어 낸다")
    void mergesDepositAndBalance() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"entr\":\"000000012500000\",\"return_code\":0}");
        transport.enqueueJson("""
                {"tot_pur_amt":"000000017598258","acnt_evlt_remn_indv_tot":[
                  {"stk_cd":"A005930","stk_nm":"삼성전자","rmnd_qty":"000000000000020",
                   "pur_pric":"000000000071000","cur_prc":"000000073500"},
                  {"stk_cd":"A000660","stk_nm":"SK하이닉스","rmnd_qty":"000000000000000",
                   "pur_pric":"000000000183000","cur_prc":"000000190500"}
                ],"return_code":0}""");

        Account account = client.fetchAccount("12345678901");

        assertEquals(0, new BigDecimal("12500000").compareTo(account.balance().cash()));
        assertEquals(1, account.positions().size(), "수량 0인 종목은 제외해야 합니다");
        assertEquals("005930", account.positions().get(0).symbol(), "A 접두어를 떼야 합니다");
        assertEquals(20L, account.positions().get(0).quantity());
        assertEquals("kt00001", transport.requests.get(1).headers().get("api-id"));
        assertEquals("kt00018", transport.requests.get(2).headers().get("api-id"));
    }

    // ------------------------------------------------------------------
    // 주문
    // ------------------------------------------------------------------

    @Test
    @DisplayName("주문수량과 체결수량으로 부분 체결을 판단한다")
    void derivesPartiallyFilledFromQuantities() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"acnt_ord_cntr_prst_array":[
                  {"ord_no":"0000050","stk_cd":"A005930","stk_nm":"삼성전자","io_tp_nm":"현금매수",
                   "ord_qty":"0000000010","ord_uv":"0000070000","cntr_qty":"0000000004",
                   "cntr_uv":"0000069800","acpt_tp":"접수"}
                ],"return_code":0}""");

        List<Order> orders = client.fetchOrders("12345678901");

        assertEquals(1, orders.size());
        Order order = orders.get(0);
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
        assertEquals("005930", order.symbol());
        assertEquals(4L, order.filledQuantity());
        assertEquals(6L, order.remainingQuantity());
    }

    @Test
    @DisplayName("매수와 매도는 서로 다른 TR 로 보낸다")
    void usesSeparateTrForBuyAndSell() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"ord_no\":\"0000140\",\"return_code\":0}");
        transport.enqueueJson("{\"ord_no\":\"0000141\",\"return_code\":0}");

        String buyId = client.placeOrder("12345678901",
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 10, new BigDecimal("70000")));
        String sellId = client.placeOrder("12345678901",
                OrderCommand.limit("005930", "삼성전자", OrderSide.SELL, 5, new BigDecimal("71000")));

        assertEquals("0000140", buyId);
        assertEquals("0000141", sellId);
        assertEquals("kt10000", transport.requests.get(1).headers().get("api-id"));
        assertEquals("kt10001", transport.requests.get(2).headers().get("api-id"));
        assertTrue(transport.requests.get(1).body().contains("\"trde_tp\":\"0\""));
        assertTrue(transport.requests.get(1).body().contains("\"dmst_stex_tp\":\"KRX\""));
    }

    @Test
    @DisplayName("시장가 주문은 주문단가를 비우고 매매구분 3을 보낸다")
    void sendsMarketOrderWithoutPrice() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"ord_no\":\"0000142\",\"return_code\":0}");

        client.placeOrder("12345678901", OrderCommand.market("005930", "삼성전자", OrderSide.BUY, 10));

        String body = transport.requests.get(1).body();
        assertTrue(body.contains("\"trde_tp\":\"3\""));
        assertTrue(body.contains("\"ord_uv\":\"\""));
    }

    @Test
    @DisplayName("취소는 원주문번호를 보낸다")
    void cancelsUsingOriginalOrderNumber() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"ord_no\":\"0000143\",\"base_orig_ord_no\":\"0000140\",\"return_code\":0}");

        client.cancelOrder("12345678901", "0000140");

        assertEquals("kt10003", transport.requests.get(1).headers().get("api-id"));
        assertTrue(transport.requests.get(1).body().contains("\"orig_ord_no\":\"0000140\""));
    }

    @Test
    @DisplayName("실거래 설정에서 실행 인자가 없으면 요청을 보내지 않는다")
    void blocksLiveTradingWithoutExplicitFlag() {
        KiwoomProperties live = KiwoomProperties.liveTrading(properties.webSocketUrl());
        KiwoomRestClient liveClient = newClient(live, RetryPolicy.none());

        assertThrows(BrokerAuthException.class, () -> liveClient.fetchAccount("12345678901"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    @DisplayName("토큰 만료 시각이 지나면 다시 발급받는다")
    void reissuesExpiredToken() {
        TestClock clock = new TestClock(Instant.parse("2026-08-08T01:00:00Z"));
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), clock);
        KiwoomTokenProvider provider = new KiwoomTokenProvider(transport, jsonMapper, properties,
                BrokerCredentials.of("PS1a2b3c4d5e", "S9z8y7x6w5v4"), clock);

        // 2026-08-08 10:05 KST = 01:05 UTC
        transport.enqueueJson("{\"token\":\"first12345678\",\"expires_dt\":\"20260808100500\",\"return_code\":0}");
        assertEquals("first12345678", provider.token().value());

        clock.advance(java.time.Duration.ofMinutes(10));
        transport.enqueueJson("{\"token\":\"second1234567\",\"expires_dt\":\"20260808110000\",\"return_code\":0}");
        assertEquals("second1234567", provider.token().value());
    }

    /** ka10001 주식기본정보요청 응답의 일부. */
    private static String basicInfo() {
        return """
                {"stk_cd":"005930","stk_nm":"삼성전자","cur_prc":"+70700","base_pric":"70300",
                 "pred_pre":"+400","pre_sig":"2","flu_rt":"+0.57","trde_qty":"18450230",
                 "open_pric":"+69800","high_pric":"+70900","low_pric":"+69700","return_code":0}""";
    }
}
