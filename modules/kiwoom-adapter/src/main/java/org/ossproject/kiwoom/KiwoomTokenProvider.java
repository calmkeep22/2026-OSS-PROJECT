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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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

    /** 만료 정보를 해석하지 못했을 때 쓰는 보수적인 수명. */
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(10);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

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

    /**
     * 만료 정보를 초 단위로 바꾼다.
     *
     * <p>키움은 남은 초가 아니라 만료 시각을 {@code yyyyMMddHHmmss} 형식으로 준다.
     * 다른 증권사나 옛 응답이 남은 초를 줄 수도 있어 둘 다 받아들인다. 값을 해석하지
     * 못하면 짧게 잡아 다음 호출에서 다시 발급받게 한다. 만료를 놓쳐 주문이 실패하는
     * 것보다 한 번 더 발급받는 편이 낫다.
     */
    private long readExpiresIn(JsonNode root) {
        String name = properties.fields().nameOf(KiwoomField.TOKEN_EXPIRES_IN);
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return DEFAULT_LIFETIME.toSeconds();
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIFETIME.toSeconds();
        }
        raw = raw.trim();

        // yyyyMMddHHmmss 형식의 만료 시각
        if (raw.length() == 14 && raw.chars().allMatch(Character::isDigit)) {
            try {
                LocalDateTime expiry = LocalDateTime.parse(raw, EXPIRY_FORMAT);
                long seconds = Duration.between(clock.instant(), expiry.atZone(MARKET_ZONE).toInstant())
                        .toSeconds();
                return seconds > 0 ? seconds : DEFAULT_LIFETIME.toSeconds();
            } catch (RuntimeException ignored) {
                return DEFAULT_LIFETIME.toSeconds();
            }
        }

        try {
            long seconds = Long.parseLong(raw);
            return seconds > 0 ? seconds : DEFAULT_LIFETIME.toSeconds();
        } catch (NumberFormatException e) {
            return DEFAULT_LIFETIME.toSeconds();
        }
    }

    /** 자격 증명 문자열은 이 메서드 밖으로 나가지 않는다. */
    private String buildRequestBody() {
        // 키움은 시크릿 필드를 appsecret 이 아니라 secretkey 로 받는다.
        return "{\"grant_type\":\"client_credentials\",\"appkey\":\""
                + escape(credentials.appKey()) + "\",\"secretkey\":\""
                + escape(credentials.appSecret()) + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
