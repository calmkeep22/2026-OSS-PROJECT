package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.broker.BrokerCredentials;
import org.ossproject.broker.resilience.CircuitBreaker;
import org.ossproject.broker.resilience.ResilientExecutor;
import org.ossproject.broker.resilience.RetryPolicy;
import org.ossproject.broker.resilience.Sleeper;
import org.ossproject.kiwoom.http.HttpTransport;
import org.ossproject.kiwoom.http.JdkHttpTransport;
import org.ossproject.kiwoom.stream.KiwoomMarketDataStream;
import org.ossproject.secret.SecretStore;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;

/**
 * 앱 키 하나로 키움 연동 전체를 조립한다.
 *
 * <p>토큰 발급, REST 클라이언트, 실시간 스트림을 각각 만들어 연결하는 일은 순서와 의존
 * 관계가 많아 실수하기 쉽다. 이 클래스가 그 조립을 한 곳에 모아 두므로, 사용하는 쪽은
 * 환경과 키만 준비하면 된다.
 *
 * <pre>{@code
 * try (KiwoomSession session = KiwoomSession.open(KiwoomEnvironment.MOCK, secretStore)
 *         .orElseThrow(() -> new IllegalStateException("앱 키가 없습니다."))) {
 *     MarketDataStreamPort stream = session.marketDataStream();
 *     stream.addOrderBookListener(book -> ...);
 *     stream.connect();
 *     stream.subscribe(List.of("005930"));
 * }
 * }</pre>
 *
 * <p>{@link #close()} 를 부르면 스트림을 닫고 메모리에서 앱 키를 지운다.
 */
public final class KiwoomSession implements AutoCloseable {

    private final KiwoomEnvironment environment;
    private final KiwoomProperties properties;
    private final BrokerCredentials credentials;
    private final KiwoomTokenProvider tokenProvider;
    private final ResilientExecutor executor;
    private final KiwoomClient client;
    private final KiwoomMarketDataStream marketDataStream;

    private KiwoomSession(KiwoomEnvironment environment, KiwoomProperties properties,
                          BrokerCredentials credentials, KiwoomTokenProvider tokenProvider,
                          ResilientExecutor executor, KiwoomClient client,
                          KiwoomMarketDataStream marketDataStream) {
        this.environment = environment;
        this.properties = properties;
        this.credentials = credentials;
        this.tokenProvider = tokenProvider;
        this.executor = executor;
        this.client = client;
        this.marketDataStream = marketDataStream;
    }

    /**
     * 비밀 저장소나 환경 변수에서 앱 키를 찾아 연결을 준비한다.
     *
     * @return 앱 키를 찾지 못하면 비어 있는 값. 화면 계층은 이때 설정 안내를 보여 준다
     */
    public static Optional<KiwoomSession> open(KiwoomEnvironment environment, SecretStore secretStore) {
        return new KiwoomCredentialSource(secretStore).find(environment)
                .map(credentials -> open(environment, credentials));
    }

    /** 앱 키를 직접 넘겨 연결을 준비한다. 자격 증명의 수명은 이 세션이 관리한다. */
    public static KiwoomSession open(KiwoomEnvironment environment, BrokerCredentials credentials) {
        return open(environment, credentials, new JdkHttpTransport(), Clock.systemDefaultZone());
    }

    /** 테스트에서 전송 계층과 시계를 바꿔 끼우기 위한 생성 경로. */
    static KiwoomSession open(KiwoomEnvironment environment, BrokerCredentials credentials,
                              HttpTransport transport, Clock clock) {
        if (environment == null) {
            throw new IllegalArgumentException("접속 환경은 필수입니다.");
        }
        if (credentials == null) {
            throw new IllegalArgumentException("자격 증명은 필수입니다.");
        }

        KiwoomProperties properties = KiwoomProperties.forEnvironment(environment);
        KiwoomJsonMapper jsonMapper = new KiwoomJsonMapper(new ObjectMapper(), properties, clock);
        KiwoomTokenProvider tokenProvider =
                new KiwoomTokenProvider(transport, jsonMapper, properties, credentials, clock);
        ResilientExecutor executor = new ResilientExecutor(
                RetryPolicy.defaults(), CircuitBreaker.defaults(clock), Sleeper.system());

        KiwoomClient client = new KiwoomClient(transport, jsonMapper, properties, tokenProvider,
                executor, clock);

        URI streamUri = URI.create(properties.webSocketUrl() + KiwoomApi.WEBSOCKET.path());
        KiwoomMarketDataStream stream =
                new KiwoomMarketDataStream(properties.withWebSocketUrl(streamUri),
                        () -> tokenProvider.token().value());

        return new KiwoomSession(environment, properties, credentials, tokenProvider, executor,
                client, stream);
    }

    public KiwoomEnvironment environment() {
        return environment;
    }

    public KiwoomProperties properties() {
        return properties;
    }

    /** 실시간 시세·호가 스트림. */
    public MarketDataStreamPort marketDataStream() {
        return marketDataStream;
    }

    /** 시세·계좌·주문 REST 호출. */
    public KiwoomClient client() {
        return client;
    }

    /** 재시도와 회로 차단이 걸린 실행기. REST 호출을 감쌀 때 쓴다. */
    public ResilientExecutor executor() {
        return executor;
    }

    /**
     * 접근 토큰을 미리 발급받아 앱 키가 올바른지 확인한다.
     *
     * <p>설정 화면에서 "연결 확인" 버튼이 이 메서드를 부르면, 사용자가 잘못된 키를
     * 저장해 두고 나중에 주문 시점에야 실패하는 상황을 막을 수 있다.
     */
    public void verifyCredentials() {
        client.authenticate();
    }

    public boolean isAuthenticated() {
        return client.isAuthenticated();
    }

    @Override
    public void close() {
        try {
            marketDataStream.close();
        } finally {
            tokenProvider.invalidate();
            credentials.close();
        }
    }
}
