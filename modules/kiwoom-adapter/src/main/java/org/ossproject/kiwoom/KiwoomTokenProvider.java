package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.broker.auth.AccessToken;
import org.ossproject.broker.error.BrokerAuthException;
import org.ossproject.broker.auth.BrokerCredentials;
import org.ossproject.kiwoom.http.HttpTextRequest;
import org.ossproject.kiwoom.http.HttpTextResponse;
import org.ossproject.kiwoom.http.HttpTransport;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 접근토큰 발급과 캐싱(au10001).
 *
 * <p>토큰은 메모리에만 둔다. 디스크에 쓰지 않으며 로그에도 남기지 않는다. 만료 1분 전부터는
 * 새로 발급받는다.
 *
 * <p>요청 본문은 {@code grant_type}, {@code appkey}, {@code secretkey} 다. 응답은 만료 시각을
 * 초가 아니라 {@code expires_dt} 절대 시각({@code yyyyMMddHHmmss}, 한국 시간)으로 준다.
 */
public final class KiwoomTokenProvider {

    private static final DateTimeFormatter EXPIRES_AT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    /** 만료 시각을 해석하지 못했을 때 보수적으로 잡는 유효 기간. */
    private static final Duration FALLBACK_LIFETIME = Duration.ofMinutes(10);

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
                .post(properties.resolve(KiwoomTr.ISSUE_TOKEN))
                .header("api-id", KiwoomTr.ISSUE_TOKEN.id())
                .jsonBody(buildRequestBody())
                .timeout(properties.requestTimeout())
                .build();

        HttpTextResponse response = transport.send(request);
        if (!response.isSuccess()) {
            throw KiwoomErrorMapper.toException("접근 토큰 발급", response);
        }

        JsonNode root = jsonMapper.parse(response.body());
        KiwoomErrorMapper.requireSuccessBody("접근 토큰 발급", root, response);
        return new AccessToken(readToken(root), readExpiresAt(root));
    }

    private String readToken(JsonNode root) {
        JsonNode node = root.get("token");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new BrokerAuthException(
                    "토큰 응답에 token 항목이 없습니다. 응답 형식을 확인해 주세요.");
        }
        return node.asText();
    }

    /**
     * {@code expires_dt} 를 시각으로 읽는다.
     *
     * <p>형식이 예상과 다르면 만료를 무한정으로 보지 않고 짧게 잡는다. 만료된 토큰으로 계속
     * 호출하다 인증 오류를 사용자에게 보여 주는 것보다, 조금 일찍 재발급하는 편이 안전하다.
     */
    private java.time.Instant readExpiresAt(JsonNode root) {
        JsonNode node = root.get("expires_dt");
        String raw = node == null || node.isNull() ? null : node.asText().trim();
        if (raw == null || raw.length() != 14) {
            return clock.instant().plus(FALLBACK_LIFETIME);
        }
        try {
            return LocalDateTime.parse(raw, EXPIRES_AT).atZone(MARKET_ZONE).toInstant();
        } catch (RuntimeException unexpectedFormat) {
            return clock.instant().plus(FALLBACK_LIFETIME);
        }
    }

    /** 자격 증명 문자열은 이 메서드 밖으로 나가지 않는다. */
    private String buildRequestBody() {
        return "{\"grant_type\":\"client_credentials\",\"appkey\":\""
                + escape(credentials.appKey()) + "\",\"secretkey\":\""
                + escape(credentials.appSecret()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
