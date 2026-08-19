package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.broker.BrokerAuthException;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.SensitiveDataMasker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.Position;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.StockDetail;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 키움 REST 클라이언트.
 *
 * <p>키움은 모든 기능을 <b>POST</b> 로 받고, 무엇을 요청하는지는 URL 이 아니라
 * {@code api-id} 헤더로 구분한다. 그래서 조회조차 GET 이 아니다. 이 규칙을 한 곳
 * ({@link #call})에 모아 두어, 기능이 늘어도 헤더를 빠뜨릴 일이 없게 했다.
 *
 * <p>401 을 받으면 토큰을 한 번 재발급받아 다시 시도한다. 토큰이 만료됐을 뿐인데
 * 사용자에게 인증 실패라고 알리면, 원인을 알 수 없는 오류로 보이기 때문이다.
 */
public final class KiwoomClient {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BASE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HttpTransport transport;
    private final KiwoomJsonMapper jsonMapper;
    private final KiwoomProperties properties;
    private final KiwoomTokenProvider tokenProvider;
    private final ResilientExecutor executor;
    private final Clock clock;

    public KiwoomClient(HttpTransport transport, KiwoomJsonMapper jsonMapper,
                        KiwoomProperties properties, KiwoomTokenProvider tokenProvider,
                        ResilientExecutor executor, Clock clock) {
        if (transport == null || jsonMapper == null || properties == null
                || tokenProvider == null || executor == null || clock == null) {
            throw new IllegalArgumentException("키움 클라이언트 구성 요소가 누락되었습니다.");
        }
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.executor = executor;
        this.clock = clock;
    }

    public String brokerId() {
        return "kiwoom";
    }

    public void authenticate() {
        executor.run("접근 토큰 발급", tokenProvider::token);
    }

    public boolean isAuthenticated() {
        return tokenProvider.hasValidToken();
    }

    // ------------------------------------------------------------------
    // 시세
    // ------------------------------------------------------------------

    /** 10단계 호가창을 조회한다. 이후 갱신은 실시간 스트림에 맡긴다. */
    public OrderBook fetchOrderBook(String symbol) {
        requireSymbol(symbol);
        return call(KiwoomApi.ORDER_BOOK, KiwoomRequests.bySymbol(symbol),
                node -> KiwoomOrderBookParser.fromRest(symbol, node, clock.instant()));
    }

    /** 현재가를 조회한다. */
    public Quote fetchQuote(String symbol) {
        requireSymbol(symbol);
        return call(KiwoomApi.STOCK_INFO, KiwoomRequests.bySymbol(symbol), node -> new Quote(
                symbol,
                required(node, "cur_prc", symbol),
                decimal(node, "base_pric"),
                decimal(node, "buy_fpr_bid"),
                decimal(node, "sel_fpr_bid"),
                0L, 0L,
                longValue(node, "trde_qty"),
                clock.instant()));
    }

    /** 종목 상세 정보를 조회한다. */
    public StockDetail fetchStockDetail(String symbol) {
        requireSymbol(symbol);
        return call(KiwoomApi.STOCK_INFO, KiwoomRequests.bySymbol(symbol), node -> {
            BigDecimal current = required(node, "cur_prc", symbol);
            BigDecimal change = decimal(node, "pred_pre");
            BigDecimal base = decimal(node, "base_pric");
            return new StockDetail(symbol, text(node, "stk_nm", symbol), current,
                    change == null ? BigDecimal.ZERO : change,
                    changeRate(current, base),
                    direction(change),
                    orZero(decimal(node, "open_pric")),
                    orZero(decimal(node, "high_pric")),
                    orZero(decimal(node, "low_pric")),
                    longValue(node, "trde_qty"),
                    clock.instant());
        });
    }

    /**
     * 일봉을 조회한다.
     *
     * <p>분봉은 다른 API({@link KiwoomApi#MINUTE_CHART})를 쓰고 요청 형식도 달라
     * 아직 지원하지 않는다. 일봉만으로도 차트와 이상 탐지는 동작한다.
     */
    public List<Candle> fetchDailyCandles(String symbol, int count) {
        requireSymbol(symbol);
        if (count <= 0) {
            throw new IllegalArgumentException("조회 개수는 1 이상이어야 합니다.");
        }
        String baseDate = LocalDate.now(clock.withZone(MARKET_ZONE)).format(BASE_DATE);
        return call(KiwoomApi.DAILY_CHART, KiwoomRequests.dailyChart(symbol, baseDate, true),
                node -> {
                    List<Candle> candles = new ArrayList<>();
                    JsonNode rows = node.get("stk_dt_pole_chart_qry");
                    if (rows == null || !rows.isArray()) {
                        return List.<Candle>of();
                    }
                    for (JsonNode row : rows) {
                        Candle candle = toCandle(row);
                        if (candle != null) {
                            candles.add(candle);
                        }
                    }
                    // 키움은 최신순으로 준다. 도메인 계약은 오래된 것부터다.
                    candles.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));
                    int from = Math.max(0, candles.size() - count);
                    return List.copyOf(candles.subList(from, candles.size()));
                });
    }

    // ------------------------------------------------------------------
    // 계좌
    // ------------------------------------------------------------------

    /**
     * 계좌 잔고를 조회한다.
     *
     * <p>키움은 예수금과 보유 종목을 서로 다른 API 로 준다. 화면에서는 하나의 계좌로
     * 보여야 하므로 두 번 호출해 합친다.
     */
    public Account fetchAccount(String accountNo) {
        requireAccount(accountNo);

        BigDecimal cash = call(KiwoomApi.DEPOSIT, KiwoomRequests.deposit(), node -> {
            BigDecimal orderable = decimal(node, "ord_alow_amt_entr");
            return orderable != null ? orderable : orZero(decimal(node, "entr"));
        });

        List<Position> positions = call(KiwoomApi.BALANCE, KiwoomRequests.balance(true), node -> {
            List<Position> found = new ArrayList<>();
            JsonNode rows = node.get("acnt_evlt_remn_indv_tot");
            if (rows == null || !rows.isArray()) {
                return List.<Position>of();
            }
            for (JsonNode row : rows) {
                long quantity = longValue(row, "rmnd_qty");
                if (quantity <= 0) {
                    continue;
                }
                BigDecimal average = orZero(decimal(row, "pur_pric"));
                BigDecimal current = decimal(row, "cur_prc");
                String code = text(row, "stk_cd", null);
                if (code == null) {
                    continue;
                }
                found.add(new Position(normalizeSymbol(code), text(row, "stk_nm", code),
                        quantity, 0L, average, current == null ? average : current));
            }
            return List.copyOf(found);
        });

        return new Account(accountNo, Balance.of(cash), positions);
    }

    // ------------------------------------------------------------------
    // 주문
    // ------------------------------------------------------------------

    /**
     * 주문을 접수하고 증권사 주문번호를 돌려준다.
     *
     * <p>매수와 매도는 본문 값이 아니라 서로 다른 API 다. 선택은
     * {@link KiwoomRequests#orderApi(OrderSide)} 한 곳에서만 이루어진다.
     */
    public String placeOrder(OrderCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        requireLiveTradingAllowed();
        KiwoomApi api = KiwoomRequests.orderApi(command.side());
        return call(api, KiwoomRequests.placeOrder(command), node -> {
            String orderId = text(node, "ord_no", null);
            if (orderId == null) {
                throw new BrokerException("주문 응답에 주문번호가 없어 접수 결과를 확인하지 못했습니다.");
            }
            return orderId;
        });
    }

    /** 주문을 취소한다. 수량이 0 이하이면 잔량 전부를 취소한다. */
    public String cancelOrder(String originalOrderId, String symbol, long quantity) {
        requireLiveTradingAllowed();
        return call(KiwoomApi.CANCEL_ORDER,
                KiwoomRequests.cancelOrder(originalOrderId, symbol, quantity),
                node -> {
                    String orderId = text(node, "ord_no", null);
                    return orderId == null ? originalOrderId : orderId;
                });
    }

    // ------------------------------------------------------------------
    // 공통 호출
    // ------------------------------------------------------------------

    private <T> T call(KiwoomApi api, String body, Function<JsonNode, T> parser) {
        return executor.call(api.displayName(), () -> {
            HttpTextResponse response = send(api, body);
            if (response.statusCode() == 401) {
                tokenProvider.invalidate();
                response = send(api, body);
            }
            if (!response.isSuccess()) {
                throw KiwoomErrorMapper.toException(api.displayName(), response);
            }
            JsonNode root = jsonMapper.parse(response.body());
            failIfBusinessError(api, root);
            return parser.apply(root);
        });
    }

    private HttpTextResponse send(KiwoomApi api, String body) {
        HttpTextRequest.Builder builder = HttpTextRequest
                .post(properties.resolve(api.path()))
                .jsonBody(body)
                .header("authorization", tokenProvider.token().asBearerHeader())
                .timeout(properties.requestTimeout());
        if (api.hasApiId()) {
            builder.header("api-id", api.apiId());
        }
        return transport.send(builder.build());
    }

    /**
     * 키움은 HTTP 200 으로 응답하면서 본문에 오류 코드를 담는다.
     *
     * <p>상태 코드만 보면 실패를 성공으로 착각하고 빈 데이터를 화면에 그리게 된다.
     */
    private void failIfBusinessError(KiwoomApi api, JsonNode root) {
        JsonNode code = root.get("return_code");
        if (code == null || code.isNull() || code.asInt() == 0) {
            return;
        }
        String message = text(root, "return_msg", "");
        throw new BrokerException(api.displayName() + " 요청이 거부되었습니다. "
                + "응답 코드 " + code.asInt() + ". " + SensitiveDataMasker.mask(message));
    }

    /**
     * 실거래 활성화 여부를 확인한다.
     *
     * <p>실전 환경에서는 {@code -Dossproject.trading.live=true} 실행 인자가 있어야만
     * 주문을 보낸다. 설정 파일이 아니라 실행 인자를 요구해 실수로 켜지는 경로를 좁혔다.
     */
    private void requireLiveTradingAllowed() {
        if (properties.paperTrading() || Boolean.getBoolean("ossproject.trading.live")) {
            return;
        }
        throw new BrokerAuthException(
                "실거래가 비활성화되어 있어 주문을 보내지 않았습니다. "
                        + "실행 인자 -Dossproject.trading.live=true 가 필요합니다.");
    }

    // ------------------------------------------------------------------
    // 값 읽기
    // ------------------------------------------------------------------

    private Candle toCandle(JsonNode row) {
        String rawDate = text(row, "dt", null);
        BigDecimal close = decimal(row, "cur_prc");
        BigDecimal open = decimal(row, "open_pric");
        BigDecimal high = decimal(row, "high_pric");
        BigDecimal low = decimal(row, "low_pric");
        if (rawDate == null || close == null || open == null || high == null || low == null) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(rawDate.trim(), BASE_DATE);
        } catch (RuntimeException e) {
            return null;
        }
        return new Candle(date.atStartOfDay(MARKET_ZONE).toInstant(), CandleInterval.DAY,
                open, high, low, close, longValue(row, "trde_qty"));
    }

    private static BigDecimal changeRate(BigDecimal current, BigDecimal base) {
        if (current == null || base == null || base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(base)
                .divide(base, 6, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static PriceDirection direction(BigDecimal change) {
        if (change == null || change.signum() == 0) {
            return PriceDirection.FLAT;
        }
        return change.signum() > 0 ? PriceDirection.UP : PriceDirection.DOWN;
    }

    private BigDecimal required(JsonNode node, String field, String symbol) {
        BigDecimal value = decimal(node, field);
        if (value == null) {
            throw new BrokerException("응답에 " + field + " 가 없어 " + symbol + " 시세를 읽지 못했습니다.");
        }
        return value;
    }

    /** 키움은 부호와 천 단위 구분자를 붙여 보낸다. 가격은 하락 시 음수로 온다. */
    private static BigDecimal decimal(JsonNode parent, String field) {
        String raw = text(parent, field, null);
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw).abs();
            return value.signum() == 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longValue(JsonNode parent, String field) {
        String raw = text(parent, field, null);
        if (raw == null) {
            return 0L;
        }
        try {
            return new BigDecimal(raw).abs().longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String text(JsonNode parent, String field, String fallback) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        String raw = node.asText();
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.replace(",", "").replace("+", "").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    /** 키움은 종목코드에 접미사(예: {@code A005930}, {@code 005930_AL})를 붙이기도 한다. */
    private static String normalizeSymbol(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : raw;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
    }

    private static void requireAccount(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("계좌번호는 필수입니다.");
        }
    }
}
