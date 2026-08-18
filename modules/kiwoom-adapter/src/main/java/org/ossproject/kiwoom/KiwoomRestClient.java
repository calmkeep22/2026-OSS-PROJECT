package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.broker.BrokerAuthException;
import org.ossproject.broker.BrokerClient;
import org.ossproject.broker.BrokerCredentials;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.SensitiveDataMasker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderType;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 키움 REST 클라이언트.
 *
 * <p>모든 호출은 {@link ResilientExecutor} 를 거쳐 재시도와 회로 차단이 적용된다.
 * 401 응답을 받으면 토큰을 한 번 재발급받고 다시 시도한다. 토큰이 만료되었을 뿐인데
 * 사용자에게 인증 실패라고 알리는 상황을 막기 위해서다.
 *
 * <p>이 클래스는 주소·경로·필드명을 {@link KiwoomProperties} 에서만 읽는다. 공식 문서에
 * 맞춰 설정만 채우면 코드 수정 없이 동작한다.
 */
public final class KiwoomRestClient implements BrokerClient {

    private static final String BROKER_ID = "kiwoom";

    private final HttpTransport transport;
    private final KiwoomJsonMapper jsonMapper;
    private final KiwoomProperties properties;
    private final KiwoomTokenProvider tokenProvider;
    private final BrokerCredentials credentials;
    private final ResilientExecutor executor;

    public KiwoomRestClient(HttpTransport transport, KiwoomJsonMapper jsonMapper,
                            KiwoomProperties properties, KiwoomTokenProvider tokenProvider,
                            BrokerCredentials credentials, ResilientExecutor executor) {
        if (transport == null || jsonMapper == null || properties == null
                || tokenProvider == null || credentials == null || executor == null) {
            throw new IllegalArgumentException("키움 클라이언트 구성 요소가 누락되었습니다.");
        }
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.credentials = credentials;
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
        URI uri = properties.resolve(properties.endpoints().quotePath(), Map.of("symbol", symbol));
        return executor.call("현재가 조회",
                () -> withAuthRetry("현재가 조회", () -> HttpTextRequest.get(uri), jsonMapper::toQuote));
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
        URI base = properties.resolve(properties.endpoints().candlePath(), Map.of("symbol", symbol));
        URI uri = URI.create(base + "?interval=" + interval.name() + "&count=" + count);
        return executor.call("봉 조회",
                () -> withAuthRetry("봉 조회", () -> HttpTextRequest.get(uri),
                        node -> jsonMapper.toCandles(node, interval)));
    }

    @Override
    public Account fetchAccount(String accountNo) {
        requireAccountNo(accountNo);
        URI uri = properties.resolve(properties.endpoints().accountPath(),
                Map.of("accountNo", accountNo));
        return executor.call("잔고 조회",
                () -> withAuthRetry("잔고 조회", () -> HttpTextRequest.get(uri),
                        node -> jsonMapper.toAccount(accountNo, node)));
    }

    @Override
    public List<Order> fetchOrders(String accountNo) {
        requireAccountNo(accountNo);
        URI uri = properties.resolve(properties.endpoints().orderListPath(),
                Map.of("accountNo", accountNo));
        return executor.call("주문 내역 조회",
                () -> withAuthRetry("주문 내역 조회", () -> HttpTextRequest.get(uri), jsonMapper::toOrders));
    }

    @Override
    public String placeOrder(String accountNo, OrderCommand command) {
        requireAccountNo(accountNo);
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        URI uri = properties.resolve(properties.endpoints().orderPath());
        String body = buildOrderBody(accountNo, command);

        return executor.call("주문 접수", () -> withAuthRetry("주문 접수",
                () -> HttpTextRequest.post(uri).jsonBody(body),
                node -> {
                    String name = properties.fields().nameOf(KiwoomField.ORDER_ID);
                    JsonNode idNode = node.get(name);
                    if (idNode == null || idNode.isNull() || idNode.asText().isBlank()) {
                        throw new BrokerException(
                                "주문 응답에 " + name + " 항목이 없어 주문 번호를 확인하지 못했습니다.");
                    }
                    return idNode.asText();
                }));
    }

    @Override
    public void cancelOrder(String accountNo, String brokerOrderId) {
        requireAccountNo(accountNo);
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            throw new IllegalArgumentException("주문 번호는 필수입니다.");
        }
        URI uri = properties.resolve(properties.endpoints().orderCancelPath());
        String body = "{\"accountNo\":\"" + escape(accountNo)
                + "\",\"orderId\":\"" + escape(brokerOrderId) + "\"}";

        executor.run("주문 취소", () -> withAuthRetry("주문 취소",
                () -> HttpTextRequest.post(uri).jsonBody(body), node -> null));
    }

    /**
     * 요청을 보내고, 401 이면 토큰을 재발급받아 한 번만 다시 시도한다.
     *
     * @param requestFactory 헤더를 붙이기 전 요청 빌더. 재시도 시 새 토큰으로 다시 만든다
     */
    private <T> T withAuthRetry(String operation,
                                java.util.function.Supplier<HttpTextRequest.Builder> requestFactory,
                                Function<JsonNode, T> parser) {
        HttpTextResponse response = send(requestFactory.get());
        if (response.statusCode() == 401) {
            tokenProvider.invalidate();
            response = send(requestFactory.get());
        }
        if (!response.isSuccess()) {
            throw KiwoomErrorMapper.toException(operation, response);
        }
        return parser.apply(jsonMapper.parse(response.body()));
    }

    private HttpTextResponse send(HttpTextRequest.Builder builder) {
        return send(builder, null);
    }

    /**
     * 요청을 보낸다.
     *
     * <p>키움은 기능을 URL 이 아니라 {@code api-id} 헤더로 구분한다. 이 헤더가 빠지면
     * 경로가 맞아도 서버가 어떤 기능인지 알 수 없어 실패한다.
     */
    private HttpTextResponse send(HttpTextRequest.Builder builder, KiwoomApi api) {
        builder.header("authorization", tokenProvider.token().asBearerHeader())
                .header("Accept", "application/json")
                .timeout(properties.requestTimeout());
        if (api != null && api.hasApiId()) {
            builder.header("api-id", api.apiId());
        }
        return transport.send(builder.build());
    }

    /**
     * 호가창을 조회한다.
     *
     * <p>화면을 처음 열 때 한 장을 받는 용도다. 이후 갱신은 실시간 스트림에 맡긴다.
     * 호가를 반복 조회하면 호출 한도를 금방 소진한다.
     */
    public org.ossproject.finance.model.OrderBook fetchOrderBook(String symbol) {
        requireSymbol(symbol);
        URI uri = properties.resolve(KiwoomApi.ORDER_BOOK.path());
        String body = "{\"stk_cd\":\"" + escape(symbol) + "\"}";

        return executor.call("호가 조회", () -> {
            HttpTextResponse response =
                    send(HttpTextRequest.post(uri).jsonBody(body), KiwoomApi.ORDER_BOOK);
            if (response.statusCode() == 401) {
                tokenProvider.invalidate();
                response = send(HttpTextRequest.post(uri).jsonBody(body), KiwoomApi.ORDER_BOOK);
            }
            if (!response.isSuccess()) {
                throw KiwoomErrorMapper.toException("호가 조회", response);
            }
            return KiwoomOrderBookParser.fromRest(symbol, jsonMapper.parse(response.body()),
                    java.time.Instant.now());
        });
    }

    private String buildOrderBody(String accountNo, OrderCommand command) {
        String sideCode = properties.orderSideCodes().get(command.side());
        String typeCode = properties.orderTypeCodes().get(command.type());
        if (sideCode == null || typeCode == null) {
            throw new BrokerException(
                    "매매 구분 또는 주문 유형 코드가 설정되지 않았습니다. KiwoomProperties 를 확인해 주세요.");
        }
        StringBuilder sb = new StringBuilder("{")
                .append("\"accountNo\":\"").append(escape(accountNo)).append("\",")
                .append("\"symbol\":\"").append(escape(command.symbol())).append("\",")
                .append("\"side\":\"").append(escape(sideCode)).append("\",")
                .append("\"orderType\":\"").append(escape(typeCode)).append("\",")
                .append("\"quantity\":").append(command.quantity());
        if (command.type() == OrderType.LIMIT) {
            sb.append(",\"price\":").append(command.limitPrice().toPlainString());
        }
        return sb.append('}').toString();
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
