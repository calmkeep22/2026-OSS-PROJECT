package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.broker.BrokerAuthException;
import org.ossproject.broker.BrokerCredentials;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.resilience.CircuitBreaker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.broker.resilience.Sleeper;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiwoomClientTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String TOKEN_BODY = "{\"token\":\"tok123456\",\"expires_dt\":\"20260820010000\"}";

    /** 미리 준비한 응답을 순서대로 돌려주는 전송 스텁. */
    private static final class StubTransport implements HttpTransport {
        private final Deque<HttpTextResponse> responses = new ArrayDeque<>();
        private final List<HttpTextRequest> requests = new ArrayList<>();

        void enqueueJson(String body) {
            responses.addLast(HttpTextResponse.of(200, body));
        }

        void enqueue(HttpTextResponse response) {
            responses.addLast(response);
        }

        @Override
        public HttpTextResponse send(HttpTextRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                throw new IllegalStateException("준비된 응답이 없습니다. " + request.uri());
            }
            return responses.removeFirst();
        }
    }

    private StubTransport transport;
    private KiwoomProperties properties;
    private KiwoomClient client;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        properties = KiwoomProperties.forEnvironment(KiwoomEnvironment.MOCK);
        BrokerCredentials credentials = BrokerCredentials.of("appkey123456", "secret123456");
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), properties, CLOCK);
        KiwoomTokenProvider tokenProvider =
                new KiwoomTokenProvider(transport, jsonMapper, properties, credentials, CLOCK);
        ResilientExecutor executor = new ResilientExecutor(
                RetryPolicy.immediate(3), CircuitBreaker.defaults(CLOCK), Sleeper.none());
        client = new KiwoomClient(transport, jsonMapper, properties, tokenProvider, executor, CLOCK);
    }

    @Test
    @DisplayName("모든 요청에 api-id 헤더와 POST 를 쓴다")
    void sendsApiIdHeader() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"stk_cd\":\"005930\",\"sel_fpr_bid\":\"73500\",\"sel_fpr_req\":\"180\","
                + "\"buy_fpr_bid\":\"73400\",\"buy_fpr_req\":\"310\"}");

        client.fetchOrderBook("005930");

        HttpTextRequest request = transport.requests.get(1);
        assertEquals("POST", request.method());
        assertEquals("ka10004", request.headers().get("api-id"));
    }

    @Test
    @DisplayName("호가창을 10단계까지 조회한다")
    void fetchesOrderBook() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"sel_fpr_bid\":\"73500\",\"sel_fpr_req\":\"180\","
                + "\"buy_fpr_bid\":\"73400\",\"buy_fpr_req\":\"310\","
                + "\"tot_sel_req\":\"180\",\"tot_buy_req\":\"310\"}");

        OrderBook book = client.fetchOrderBook("005930");

        assertEquals(0, new BigDecimal("73500").compareTo(book.bestAsk().orElseThrow()));
        assertEquals(310L, book.totalBidSize());
    }

    @Test
    @DisplayName("현재가를 조회한다")
    void fetchesQuote() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"cur_prc\":\"-73500\",\"base_pric\":\"71200\","
                + "\"buy_fpr_bid\":\"73400\",\"sel_fpr_bid\":\"73500\",\"trde_qty\":\"18450230\"}");

        Quote quote = client.fetchQuote("005930");

        assertEquals(0, new BigDecimal("73500").compareTo(quote.price()));
        assertEquals(18_450_230L, quote.cumulativeVolume());
    }

    @Test
    @DisplayName("종목 상세를 조회하고 등락률을 계산한다")
    void fetchesStockDetail() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"stk_nm\":\"삼성전자\",\"cur_prc\":\"73500\",\"base_pric\":\"70000\","
                + "\"pred_pre\":\"3500\",\"open_pric\":\"71800\",\"high_pric\":\"75200\","
                + "\"low_pric\":\"70100\",\"trde_qty\":\"18450230\"}");

        StockDetail detail = client.fetchStockDetail("005930");

        assertEquals("삼성전자", detail.name());
        assertEquals(0, new BigDecimal("5.00").compareTo(detail.changeRate()));
    }

    @Test
    @DisplayName("계좌 조회는 예수금과 잔고를 각각 호출해 합친다")
    void fetchesAccountFromTwoCalls() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"ord_alow_amt_entr\":\"12500000\"}");
        transport.enqueueJson("""
                {"acnt_evlt_remn_indv_tot":[
                  {"stk_cd":"A005930","stk_nm":"삼성전자","rmnd_qty":"20",
                   "pur_pric":"71000","cur_prc":"73500"},
                  {"stk_cd":"A000660","stk_nm":"SK하이닉스","rmnd_qty":"0",
                   "pur_pric":"183000","cur_prc":"190500"}
                ]}""");

        Account account = client.fetchAccount("12345678901");

        assertEquals(0, new BigDecimal("12500000").compareTo(account.balance().cash()));
        assertEquals(1, account.positions().size(), "보유 수량 0인 종목은 제외");
        assertEquals("005930", account.positions().get(0).symbol(), "종목코드 접두사를 정규화");
    }

    @Test
    @DisplayName("일봉을 오래된 순으로 정렬해 요청한 개수만큼 돌려준다")
    void fetchesDailyCandlesSortedAndTrimmed() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("""
                {"stk_dt_pole_chart_qry":[
                  {"dt":"20260819","cur_prc":"73500","open_pric":"71800","high_pric":"75200","low_pric":"70100","trde_qty":"100"},
                  {"dt":"20260818","cur_prc":"71800","open_pric":"71000","high_pric":"72000","low_pric":"70500","trde_qty":"90"},
                  {"dt":"20260817","cur_prc":"71000","open_pric":"70500","high_pric":"71500","low_pric":"70000","trde_qty":"80"}
                ]}""");

        List<Candle> candles = client.fetchDailyCandles("005930", 2);

        assertEquals(2, candles.size());
        assertTrue(candles.get(0).timestamp().isBefore(candles.get(1).timestamp()));
        assertEquals(0, new BigDecimal("73500").compareTo(candles.get(1).close()));
    }

    @Test
    @DisplayName("모의투자에서는 실행 인자 없이도 주문을 접수한다")
    void placesOrderInPaperTrading() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"ord_no\":\"K2001\",\"return_code\":0}");

        String orderId = client.placeOrder(
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 10, new BigDecimal("73500")));

        assertEquals("K2001", orderId);
        assertEquals("kt10000", transport.requests.get(1).headers().get("api-id"));
    }

    @Test
    @DisplayName("매도 주문은 매도 API 로 간다")
    void placesSellOrderWithSellApi() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"ord_no\":\"K2002\",\"return_code\":0}");

        client.placeOrder(OrderCommand.market("005930", "삼성전자", OrderSide.SELL, 5));

        assertEquals("kt10001", transport.requests.get(1).headers().get("api-id"));
    }

    @Test
    @DisplayName("실전 환경은 실행 인자 없이는 주문을 보내지 않는다")
    void blocksLiveOrderWithoutFlag() {
        KiwoomProperties live = KiwoomProperties.forEnvironment(KiwoomEnvironment.REAL);
        BrokerCredentials credentials = BrokerCredentials.of("appkey123456", "secret123456");
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), live, CLOCK);
        KiwoomClient liveClient = new KiwoomClient(transport, jsonMapper, live,
                new KiwoomTokenProvider(transport, jsonMapper, live, credentials, CLOCK),
                new ResilientExecutor(RetryPolicy.none(), CircuitBreaker.defaults(CLOCK), Sleeper.none()),
                CLOCK);

        assertThrows(BrokerAuthException.class, () -> liveClient.placeOrder(
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 1, new BigDecimal("73500"))));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    @DisplayName("HTTP 200 이어도 응답 코드가 오류면 실패로 처리한다")
    void treatsBusinessErrorAsFailure() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"return_code\":5,\"return_msg\":\"주문가능금액이 부족합니다\"}");

        BrokerException thrown = assertThrows(BrokerException.class, () -> client.placeOrder(
                OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, 10, new BigDecimal("73500"))));

        assertTrue(thrown.getMessage().contains("주문가능금액"));
    }

    @Test
    @DisplayName("401 을 받으면 토큰을 재발급받아 한 번 더 시도한다")
    void refreshesTokenOnUnauthorized() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueue(HttpTextResponse.of(401, "{\"message\":\"expired\"}"));
        transport.enqueueJson("{\"token\":\"newtok987\",\"expires_dt\":\"20260820010000\"}");
        transport.enqueueJson("{\"sel_fpr_bid\":\"73500\",\"sel_fpr_req\":\"180\","
                + "\"buy_fpr_bid\":\"73400\",\"buy_fpr_req\":\"310\"}");

        OrderBook book = client.fetchOrderBook("005930");

        assertEquals(0, new BigDecimal("73500").compareTo(book.bestAsk().orElseThrow()));
        assertEquals("Bearer newtok987", transport.requests.get(3).headers().get("authorization"));
    }

    @Test
    @DisplayName("취소 요청은 원주문번호를 그대로 돌려준다")
    void cancelsOrder() {
        transport.enqueueJson(TOKEN_BODY);
        transport.enqueueJson("{\"return_code\":0}");

        String orderId = client.cancelOrder("O123", "005930", 5);

        assertEquals("O123", orderId);
        assertEquals("kt10003", transport.requests.get(1).headers().get("api-id"));
    }
}
