package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.broker.BrokerCredentials;
import org.ossproject.broker.resilience.CircuitBreaker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.broker.resilience.Sleeper;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;

/**
 * 키움 시세 조회 포트를 한 번에 조립한다.
 *
 * <p>조립 루트가 HTTP 전송이나 JSON 매퍼, 재시도 정책 같은 내부 부품을 알 필요는 없다.
 * 자격증명만 넘기면 애플리케이션이 쓰는 포트를 돌려준다.
 *
 * @param stocks  종목 검색과 상세 조회
 * @param candles 차트 조회
 */
public record KiwoomMarketAdapters(StockQueryPort stocks, CandleQueryPort candles) {

    /** 모의투자 WebSocket 주소. */
    public static final URI MOCK_WEBSOCKET =
            URI.create("wss://mockapi.kiwoom.com:10000/api/dostk/websocket");

    public KiwoomMarketAdapters {
        Objects.requireNonNull(stocks, "stocks");
        Objects.requireNonNull(candles, "candles");
    }

    /**
     * 모의투자 서버에 붙는 조회 포트를 만든다.
     *
     * <p>자격증명 문자열은 이 메서드 안에서만 쓰이고 밖으로 나가지 않는다.
     *
     * @param appKey    키움 App Key
     * @param appSecret 키움 App Secret
     */
    public static KiwoomMarketAdapters mockTrading(String appKey, String appSecret) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("App Key 는 필수입니다.");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("App Secret 은 필수입니다.");
        }
        Clock clock = Clock.systemDefaultZone();
        KiwoomProperties properties = KiwoomProperties.mockTrading(MOCK_WEBSOCKET);
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), clock);
        // 화면 하나를 여는 동안에도 여러 조회가 나가므로 요청 간격을 강제한다.
        // 한도에 걸려 재시도하는 것보다 처음부터 벌려 보내는 편이 빠르다.
        org.ossproject.kiwoom.http.HttpTransport transport =
                new org.ossproject.kiwoom.http.RateLimitedHttpTransport(
                        new org.ossproject.kiwoom.http.JdkHttpTransport());

        KiwoomRestClient client = new KiwoomRestClient(transport, jsonMapper, properties,
                new KiwoomTokenProvider(transport, jsonMapper, properties,
                        BrokerCredentials.of(appKey, appSecret), clock),
                new ResilientExecutor(RetryPolicy.defaults(), CircuitBreaker.defaults(clock),
                        Sleeper.system()));

        return new KiwoomMarketAdapters(
                new KiwoomStockQueryAdapter(client), new KiwoomCandleQueryAdapter(client));
    }
}
