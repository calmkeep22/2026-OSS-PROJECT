package org.ossproject.kiwoom.config;

import org.ossproject.kiwoom.mapping.KiwoomErrorMapper;
import org.ossproject.kiwoom.mapping.KiwoomJsonMapper;
import org.ossproject.kiwoom.mapping.KiwoomTr;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.broker.error.BrokerAuthException;
import org.ossproject.broker.BrokerClient;
import org.ossproject.broker.error.BrokerException;
import org.ossproject.broker.auth.SensitiveDataMasker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.order.Order;
import org.ossproject.finance.model.orderbook.OrderBook;
import org.ossproject.finance.model.market.Trade;
import org.ossproject.finance.model.order.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.order.OrderType;
import org.ossproject.finance.model.market.Quote;
import org.ossproject.finance.model.market.StockDetail;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * 키움 REST 클라이언트.
 *
 * <p>모든 TR은 POST 이고, 어떤 조회인지는 {@code api-id} 헤더가 정한다. 경로는 업무 카테고리
 * 하나뿐이라 경로만 봐서는 무슨 요청인지 알 수 없다. TR 목록은 {@link KiwoomTr} 를 참고한다.
 *
 * <p>모든 호출은 {@link ResilientExecutor} 를 거쳐 재시도와 회로 차단이 적용된다. 401 응답을
 * 받으면 토큰을 한 번 재발급받고 다시 시도한다. 토큰이 만료되었을 뿐인데 사용자에게 인증
 * 실패라고 알리는 상황을 막기 위해서다.
 *
 * <p>주문 전송은 재시도하지 않는다. 응답을 못 받은 주문을 다시 보내면 중복 체결이 날 수 있다.
 */
public final class KiwoomRestClient implements BrokerClient {

    private static final String BROKER_ID = "kiwoom";
    private static final DateTimeFormatter BASE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    private final HttpTransport transport;
    private final KiwoomJsonMapper jsonMapper;
    private final KiwoomProperties properties;
    private final KiwoomTokenProvider tokenProvider;
    private final ResilientExecutor executor;

    public KiwoomRestClient(HttpTransport transport, KiwoomJsonMapper jsonMapper,
                            KiwoomProperties properties, KiwoomTokenProvider tokenProvider,
                            ResilientExecutor executor) {
        if (transport == null || jsonMapper == null || properties == null
                || tokenProvider == null || executor == null) {
            throw new IllegalArgumentException("키움 클라이언트 구성 요소가 누락되었습니다.");
        }
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.executor = executor;
    }

    @Override
    public String brokerId() {
        return BROKER_ID;
    }

    @Override
    public void authenticate() {
        executor.run("접근 토큰 발급", tokenProvider::token);
    }

    @Override
    public boolean isAuthenticated() {
        return tokenProvider.hasValidToken();
    }

    @Override
    public Quote fetchQuote(String symbol) {
        requireSymbol(symbol);
        String body = "{\"stk_cd\":\"" + escape(symbol) + "\"}";
        return executor.call("현재가 조회", () ->
                call("현재가 조회", KiwoomTr.STOCK_BASIC_INFO, body, jsonMapper::toQuote));
    }

    /**
     * 호가창 한 장을 조회한다.
     *
     * <p>실시간 구독만으로는 다음 호가가 올 때까지 화면이 비어 있다. 장 시간 외에는
     * 영영 오지 않는다. 화면을 열 때 한 장을 받아 두고 그 뒤로 실시간으로 잇는다.
     */
    public OrderBook fetchOrderBook(String symbol) {
        requireSymbol(symbol);
        String body = "{\"stk_cd\":\"" + escape(symbol) + "\"}";
        return executor.call("호가 조회", () -> call("호가 조회", KiwoomTr.ORDER_BOOK, body,
                root -> jsonMapper.toOrderBook(symbol, root)));
    }

    /**
     * 최근 체결 내역을 조회한다.
     *
     * <p>실시간 구독만으로는 다음 체결이 올 때까지 목록이 비어 있다. 장 시간 외에는 영영
     * 오지 않으므로 화면을 열 때 한 번 받아 둔다.
     */
    public List<Trade> fetchRecentTrades(String symbol) {
        requireSymbol(symbol);
        String body = "{\"stk_cd\":\"" + escape(symbol) + "\"}";
        return executor.call("체결 조회", () -> call("체결 조회", KiwoomTr.TRADE_HISTORY, body,
                root -> jsonMapper.toTrades(symbol, root)));
    }

    /** 화면용 상세. {@link #fetchQuote(String)} 와 같은 TR 을 쓰지만 더 많은 값을 담는다. */
    public StockDetail fetchStockDetail(String symbol) {
        requireSymbol(symbol);
        String body = "{\"stk_cd\":\"" + escape(symbol) + "\"}";
        return executor.call("종목 상세 조회", () ->
                call("종목 상세 조회", KiwoomTr.STOCK_BASIC_INFO, body, jsonMapper::toStockDetail));
    }

    @Override
    public List<Candle> fetchCandles(String symbol, CandleInterval interval, int count) {
        requireSymbol(symbol);
        if (interval == null) {
            throw new IllegalArgumentException("봉 주기는 필수입니다.");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("조회 개수는 1 이상이어야 합니다.");
        }
        KiwoomTr tr = KiwoomTr.chartFor(interval);
        String today = LocalDate.now(MARKET_ZONE).format(BASE_DATE);
        String body = interval.isIntraday()
                ? "{\"stk_cd\":\"" + escape(symbol) + "\",\"tic_scope\":\""
                        + KiwoomTr.tickScopeOf(interval) + "\",\"upd_stkpc_tp\":\""
                        + properties.adjustedPriceCode() + "\"}"
                : "{\"stk_cd\":\"" + escape(symbol) + "\",\"base_dt\":\"" + today
                        + "\",\"upd_stkpc_tp\":\"" + properties.adjustedPriceCode() + "\"}";

        return executor.call("봉 조회", () -> {
            List<Candle> candles = call("봉 조회", tr, body, node -> jsonMapper.toCandles(node, interval));
            // 키움은 한 번에 주는 개수를 지정할 수 없다. 최신 count 개만 남긴다.
            return candles.size() <= count
                    ? candles
                    : List.copyOf(candles.subList(candles.size() - count, candles.size()));
        });
    }

    @Override
    public Account fetchAccount(String accountNo) {
        requireAccountNo(accountNo);
        String depositBody = "{\"qry_tp\":\"2\"}";
        String balanceBody = "{\"qry_tp\":\"1\",\"dmst_stex_tp\":\"" + properties.exchange() + "\"}";
        return executor.call("잔고 조회", () -> {
            JsonNode deposit = call("예수금 조회", KiwoomTr.DEPOSIT_DETAIL, depositBody, node -> node);
            JsonNode balance = call("잔고 조회", KiwoomTr.BALANCE_DETAIL, balanceBody, node -> node);
            return jsonMapper.toAccount(accountNo, deposit, balance);
        });
    }

    @Override
    public List<Order> fetchOrders(String accountNo) {
        requireAccountNo(accountNo);
        String body = "{\"ord_dt\":\"\",\"stk_bond_tp\":\"1\",\"mrkt_tp\":\"0\",\"sell_tp\":\"0\","
                + "\"qry_tp\":\"0\",\"stk_cd\":\"\",\"fr_ord_no\":\"\",\"dmst_stex_tp\":\""
                + properties.exchange() + "\"}";
        return executor.call("주문 내역 조회", () ->
                call("주문 내역 조회", KiwoomTr.ORDER_STATUS, body, jsonMapper::toOrders));
    }

    /**
     * 주문을 접수하고 증권사 주문번호를 돌려준다.
     *
     * <p>매수와 매도는 서로 다른 TR 이다. 재시도하지 않는다.
     */
    @Override
    public String placeOrder(String accountNo, OrderCommand command) {
        requireAccountNo(accountNo);
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        KiwoomTr tr = command.side() == OrderSide.SELL ? KiwoomTr.ORDER_SELL : KiwoomTr.ORDER_BUY;
        String body = "{\"dmst_stex_tp\":\"" + properties.exchange() + "\","
                + "\"stk_cd\":\"" + escape(command.symbol()) + "\","
                + "\"ord_qty\":\"" + command.quantity() + "\","
                + "\"ord_uv\":\"" + limitPriceOf(command) + "\","
                + "\"trde_tp\":\"" + tradeTypeOf(command) + "\","
                + "\"cond_uv\":\"\"}";
        return call("주문 접수", tr, body, jsonMapper::toOrderId);
    }

    /**
     * 주문을 취소한다. 잔량 전부를 취소한다. 재시도하지 않는다.
     *
     * <p>취소 TR 은 종목코드도 함께 요구하므로 {@link #cancelOrder(String, String, String)} 를
     * 쓰는 편이 안전하다. 이 메서드는 종목코드를 비워 보낸다.
     */
    @Override
    public void cancelOrder(String accountNo, String brokerOrderId) {
        cancelOrder(accountNo, brokerOrderId, "");
    }

    /**
     * 종목코드까지 지정해 주문을 취소한다.
     *
     * @param symbol 원주문의 종목코드
     */
    public void cancelOrder(String accountNo, String brokerOrderId, String symbol) {
        requireAccountNo(accountNo);
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            throw new IllegalArgumentException("주문 번호는 필수입니다.");
        }
        String body = "{\"dmst_stex_tp\":\"" + properties.exchange() + "\","
                + "\"orig_ord_no\":\"" + escape(brokerOrderId) + "\","
                + "\"stk_cd\":\"" + escape(symbol == null ? "" : symbol) + "\","
                + "\"cncl_qty\":\"0\"}";
        call("주문 취소", KiwoomTr.ORDER_CANCEL, body, node -> null);
    }

    /** 지정가는 주문단가를 보내고 시장가는 빈 값을 보낸다. */
    private static String limitPriceOf(OrderCommand command) {
        return command.type() == OrderType.LIMIT && command.limitPrice() != null
                ? command.limitPrice().stripTrailingZeros().toPlainString()
                : "";
    }

    /** {@code trde_tp} 는 0:보통(지정가), 3:시장가 다. */
    private static String tradeTypeOf(OrderCommand command) {
        return command.type() == OrderType.MARKET ? "3" : "0";
    }

    /**
     * 도메인 모델로 옮기지 않는 TR 을 호출하고 응답 본문을 그대로 돌려준다.
     *
     * <p>종목 목록처럼 이 클라이언트가 전용 메서드를 두지 않는 조회에 쓴다. 재시도와 회로
     * 차단, 토큰 재발급, 본문 오류 검사는 다른 호출과 똑같이 적용된다.
     *
     * @param operation 사용자에게 보여 줄 작업 이름
     */
    public JsonNode callRaw(String operation, KiwoomTr tr, String body) {
        if (tr == null) {
            throw new IllegalArgumentException("TR 은 필수입니다.");
        }
        return executor.call(operation, () -> call(operation, tr, body, node -> node));
    }

    /**
     * TR 을 호출하고 응답 본문을 해석한다.
     *
     * <p>401 이면 토큰을 재발급받아 한 번만 다시 시도한다. HTTP 성공이라도 {@code return_code}
     * 를 확인한다. 키움은 업무 오류를 200 응답으로 알리기 때문이다.
     */
    private <T> T call(String operation, KiwoomTr tr, String body, Function<JsonNode, T> parser) {
        HttpTextResponse response = send(tr, body);
        if (response.statusCode() == 401) {
            tokenProvider.invalidate();
            response = send(tr, body);
        }
        if (!response.isSuccess()) {
            throw KiwoomErrorMapper.toException(operation, response);
        }
        JsonNode root = jsonMapper.parse(response.body());
        KiwoomErrorMapper.requireSuccessBody(operation, root, response);
        return parser.apply(root);
    }

    private HttpTextResponse send(KiwoomTr tr, String body) {
        HttpTextRequest request = HttpTextRequest.post(properties.resolve(tr))
                .header("authorization", tokenProvider.token().asBearerHeader())
                .header("api-id", tr.id())
                .jsonBody(body)
                .timeout(properties.requestTimeout())
                .build();
        return transport.send(request);
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
    }

    private void requireAccountNo(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다.");
        }
        if (!properties.paperTrading() && !isRealTradingEnabled()) {
            throw new BrokerAuthException(
                    "실거래가 비활성화되어 있습니다. 계좌 " + SensitiveDataMasker.maskAccountNo(accountNo)
                            + " 에 대한 요청을 보내지 않았습니다.");
        }
    }

    /**
     * 실거래 활성화 여부.
     *
     * <p>기본은 비활성화다. 실거래를 열려면 JVM 인자로
     * {@code -Dossproject.trading.live=true} 를 명시해야 한다. 설정 파일 한 줄이 아니라
     * 실행 인자를 요구하는 이유는, 실수로 켜지는 경로를 최대한 좁히기 위해서다.
     */
    private boolean isRealTradingEnabled() {
        return Boolean.getBoolean("ossproject.trading.live");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        tokenProvider.invalidate();
    }
}
