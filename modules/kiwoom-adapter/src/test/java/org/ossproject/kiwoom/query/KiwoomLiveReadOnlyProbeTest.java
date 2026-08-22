package org.ossproject.kiwoom.query;

import org.ossproject.kiwoom.config.KiwoomProperties;
import org.ossproject.kiwoom.config.KiwoomRestClient;
import org.ossproject.kiwoom.config.KiwoomTokenProvider;
import org.ossproject.kiwoom.mapping.KiwoomJsonMapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.ossproject.broker.auth.BrokerCredentials;
import org.ossproject.broker.error.BrokerException;
import org.ossproject.broker.resilience.CircuitBreaker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.broker.resilience.Sleeper;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.StockDetail;
import org.ossproject.kiwoom.http.JdkHttpTransport;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모의투자 서버에 실제로 붙어 어댑터를 검증한다.
 *
 * <p>문서만 보고 맞춘 매핑은 실제 응답과 다를 수 있다. 이 검증은 그 차이를 조기에 잡는다.
 *
 * <p><b>조회 전용이다.</b> 주문 전송 TR은 부르지 않는다. 잘못 실행해도 주문이 나가지 않아야
 * 하기 때문이다.
 *
 * <p>검사를 여러 메서드로 나누지 않고 하나로 묶은 이유는 호출 한도 때문이다. 모의투자 서버는
 * TR 당 유량이 1이라, 메서드마다 클라이언트를 새로 만들면 토큰 발급부터 한도에 걸린다.
 * 토큰 하나를 재사용하고 호출 사이에 간격을 둔다.
 *
 * <p>자격증명 환경변수가 없으면 통째로 건너뛴다. CI와 다른 개발자의 빌드는 영향을 받지 않는다.
 *
 * <pre>
 *   ./gradlew.bat :modules:kiwoom-adapter:test --tests "*KiwoomLiveReadOnlyProbeTest*" -i
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "KIWOOM_APP_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "KIWOOM_APP_SECRET", matches = ".+")
class KiwoomLiveReadOnlyProbeTest {

    /** 삼성전자. 거래정지 가능성이 가장 낮은 종목으로 고정한다. */
    private static final String SYMBOL = "005930";

    /** 호출 사이 간격. 모의투자 서버의 유량 제한을 넘지 않기 위한 값이다. */
    private static final Duration CALL_SPACING = Duration.ofMillis(1200);

    @Test
    @DisplayName("[실서버] 조회 TR 매핑을 실제 응답으로 검증한다")
    void verifiesReadOnlyMappingsAgainstMockServer() throws Exception {
        KiwoomProperties properties = KiwoomProperties.mockTrading(
                URI.create("wss://mockapi.kiwoom.com:10000/api/dostk/websocket"));
        Clock clock = Clock.systemDefaultZone();
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), clock);
        BrokerCredentials credentials = BrokerCredentials.of(
                System.getenv("KIWOOM_APP_KEY"), System.getenv("KIWOOM_APP_SECRET"));
        JdkHttpTransport transport = new JdkHttpTransport();

        try (KiwoomRestClient client = new KiwoomRestClient(transport, jsonMapper, properties,
                new KiwoomTokenProvider(transport, jsonMapper, properties, credentials, clock),
                new ResilientExecutor(RetryPolicy.immediate(2), CircuitBreaker.defaults(clock),
                        Sleeper.none()))) {

            // 1. 토큰 --------------------------------------------------
            client.authenticate();
            assertTrue(client.isAuthenticated(), "토큰이 발급되어야 합니다");
            System.out.println("[probe] token  발급 완료");

            // 2. 현재가 (ka10001) ---------------------------------------
            pause();
            Quote quote = client.fetchQuote(SYMBOL);
            assertEquals(SYMBOL, quote.symbol());
            assertTrue(quote.price().signum() > 0, "현재가는 0보다 커야 합니다");
            System.out.println("[probe] quote  price=" + quote.price()
                    + "  volume=" + quote.cumulativeVolume());

            // 3. 상세 (ka10001) -----------------------------------------
            pause();
            StockDetail detail = client.fetchStockDetail(SYMBOL);
            assertEquals(SYMBOL, detail.symbol());
            assertFalse(detail.name().isBlank(), "종목명이 있어야 합니다");
            assertTrue(detail.low().compareTo(detail.high()) <= 0, "저가가 고가보다 높을 수 없습니다");
            assertTrue(detail.currentPrice().compareTo(detail.low()) >= 0
                            && detail.currentPrice().compareTo(detail.high()) <= 0,
                    "현재가가 당일 저가와 고가 사이에 있어야 합니다. 부호 접두 처리를 확인하세요");
            System.out.println("[probe] detail " + detail.name()
                    + "  cur=" + detail.currentPrice() + "  open=" + detail.open()
                    + "  high=" + detail.high() + "  low=" + detail.low()
                    + "  vol=" + detail.volume());

            // 4. 일봉 (ka10081) -----------------------------------------
            pause();
            List<Candle> daily = client.fetchCandles(SYMBOL, CandleInterval.DAY, 10);
            assertFalse(daily.isEmpty(), "일봉이 비어 있으면 배열 키 매핑이 틀린 것입니다");
            assertTrue(daily.size() <= 10, "요청 개수를 넘으면 안 됩니다");
            assertOldestFirst(daily);
            Candle latestDay = daily.get(daily.size() - 1);
            assertTrue(latestDay.close().signum() > 0);
            assertTrue(latestDay.low().compareTo(latestDay.high()) <= 0);
            System.out.println("[probe] day    " + daily.size() + "개  최신 " + latestDay.timestamp()
                    + "  종가=" + latestDay.close() + "  거래량=" + latestDay.volume());

            // 5. 분봉 (ka10080) -----------------------------------------
            pause();
            List<Candle> minutes = client.fetchCandles(SYMBOL, CandleInterval.MINUTE_5, 10);
            assertFalse(minutes.isEmpty(), "분봉이 비어 있으면 배열 키 매핑이 틀린 것입니다");
            assertOldestFirst(minutes);
            Candle latestMinute = minutes.get(minutes.size() - 1);
            assertNotNull(latestMinute.timestamp());
            assertTrue(latestMinute.close().signum() > 0);
            System.out.println("[probe] min5   " + minutes.size() + "개  최신 "
                    + latestMinute.timestamp() + "  종가=" + latestMinute.close());

            // 6. 없는 종목 -----------------------------------------------
            pause();
            BrokerException thrown = assertThrows(BrokerException.class,
                    () -> client.fetchQuote("000000"),
                    "없는 종목은 빈 값 대신 실패해야 합니다");
            System.out.println("[probe] unknown -> " + thrown.getClass().getSimpleName());
        }
    }

    private static void assertOldestFirst(List<Candle> candles) {
        for (int index = 1; index < candles.size(); index++) {
            assertTrue(candles.get(index - 1).timestamp().isBefore(candles.get(index).timestamp()),
                    "봉은 오래된 것부터 와야 합니다");
        }
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(CALL_SPACING.toMillis());
    }
}
