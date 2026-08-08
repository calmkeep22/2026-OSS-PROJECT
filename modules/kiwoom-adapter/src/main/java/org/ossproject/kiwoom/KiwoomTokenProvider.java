package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.broker.AccessToken;
import org.ossproject.broker.BrokerAuthException;
import org.ossproject.broker.BrokerCredentials;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.time.Clock;
import java.time.Duration;

/**
 * 액세스 토큰 발급과 캐싱.
 *
 * <p>토큰은 메모리에만 둔다. 디스크에 쓰지 않으며 로그에도 남기지 않는다. 만료 1분 전부터는
 * 새로 발급받는다.
 *
 * <p>요청 본문의 필드 이름({@code grant_type}, {@code appkey}, {@code appsecret})은
 * OAuth2 client_credentials 관례를 따랐다. 키움 공식 문서와 다르면 이 클래스의
 * {@link #buildRequestBody()} 만 고치면 된다.
 */
public final class KiwoomTokenProvider {

    private final HttpTransport transport;
    private final KiwoomJsonMapper jsonMapper;
    private final KiwoomProperties properties;
    private final BrokerCredentials credentials;
    private final Clock clock;

    private AccessToken token;

    public KiwoomTokenProvider(HttpTransport transport, KiwoomJsonMapper jsonMapper,
                               KiwoomProperties properties, BrokerCredentials credentials, Clock clock) {
        if (transport == null) {
            throw new IllegalArgumentException("HTTP 전송은 필수입니다.");
        }
        if (jsonMapper == null) {
            throw new IllegalArgumentException("JSON 매퍼는 필수입니다.");
        }
        if (properties == null) {
            throw new IllegalArgumentException("설정은 필수입니다.");
        }
        if (credentials == null) {
            throw new IllegalArgumentException("자격 증명은 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.transport = transport;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.credentials = credentials;
        this.clock = clock;
    }

    /** 유효한 토큰을 돌려준다. 없거나 곧 만료되면 새로 발급받는다. */
    public synchronized AccessToken token() {
        if (token == null || token.needsRefresh(clock)) {
            token = issue();
        }
        return token;
    }

    public synchronized boolean hasValidToken() {
        return token != null && !token.needsRefresh(clock);
    }

    /** 401 응답을 받았을 때 캐시를 버리고 다음 호출에서 재발급하게 한다. */
    public synchronized void invalidate() {
        token = null;
    }

    private AccessToken issue() {
        HttpTextRequest request = HttpTextRequest
                .post(properties.resolve(properties.endpoints().tokenPath()))
                .jsonBody(buildRequestBody())
                .timeout(properties.requestTimeout())
                .build();

        HttpTextResponse response = transport.send(request);
        if (!response.isSuccess()) {
            throw KiwoomErrorMapper.toException("접근 토큰 발급", response);
        }

        JsonNode root = jsonMapper.parse(response.body());
        String value = readToken(root);
        long expiresIn = readExpiresIn(root);
        return new AccessToken(value, clock.instant().plus(Duration.ofSeconds(expiresIn)));
    }

    private String readToken(JsonNode root) {
        String name = properties.fields().nameOf(KiwoomField.TOKEN_VALUE);
        JsonNode node = root.get(name);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new BrokerAuthException(
                    "토큰 응답에 " + name + " 항목이 없습니다. 필드 대응표를 확인해 주세요.");
        }
        return node.asText();
    }

    private long readExpiresIn(JsonNode root) {
        String name = properties.fields().nameOf(KiwoomField.TOKEN_EXPIRES_IN);
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            // 만료 시간을 알려 주지 않으면 보수적으로 짧게 잡는다.
            return Duration.ofMinutes(10).toSeconds();
        }
        long seconds = node.asLong();
        return seconds > 0 ? seconds : Duration.ofMinutes(10).toSeconds();
    }

    /** 자격 증명 문자열은 이 메서드 밖으로 나가지 않는다. */
    private String buildRequestBody() {
        return "{\"grant_type\":\"client_credentials\",\"appkey\":\""
                + escape(credentials.appKey()) + "\",\"appsecret\":\""
                + escape(credentials.appSecret()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
