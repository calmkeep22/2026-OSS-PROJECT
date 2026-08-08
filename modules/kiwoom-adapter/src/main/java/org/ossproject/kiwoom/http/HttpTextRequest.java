package org.ossproject.kiwoom.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 텍스트 본문 HTTP 요청.
 *
 * <p>{@code java.net.http} 타입을 직접 쓰지 않고 한 겹 감싼 이유는 테스트에서 네트워크 없이
 * 응답을 흉내 내기 위해서다. 실제 전송은 {@link JdkHttpTransport} 가 담당한다.
 */
public record HttpTextRequest(
        String method,
        URI uri,
        Map<String, String> headers,
        String body,
        Duration timeout
) {
    public HttpTextRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("HTTP 메서드는 필수입니다.");
        }
        if (uri == null) {
            throw new IllegalArgumentException("요청 주소는 필수입니다.");
        }
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("타임아웃은 0보다 커야 합니다.");
        }
    }

    public static Builder get(URI uri) {
        return new Builder("GET", uri);
    }

    public static Builder post(URI uri) {
        return new Builder("POST", uri);
    }

    /** 헤더를 순서대로 쌓기 위한 빌더. */
    public static final class Builder {
        private final String method;
        private final URI uri;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;
        private Duration timeout = Duration.ofSeconds(10);

        private Builder(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        public Builder jsonBody(String json) {
            this.body = json;
            headers.putIfAbsent("Content-Type", "application/json; charset=utf-8");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public HttpTextRequest build() {
            return new HttpTextRequest(method, uri, headers, body, timeout);
        }
    }
}
