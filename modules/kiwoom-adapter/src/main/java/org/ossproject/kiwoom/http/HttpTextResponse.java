package org.ossproject.kiwoom.http;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 텍스트 본문 HTTP 응답. */
public record HttpTextResponse(int statusCode, Map<String, String> headers, String body) {

    public HttpTextResponse {
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        body = body == null ? "" : body;
    }

    public static HttpTextResponse of(int statusCode, String body) {
        return new HttpTextResponse(statusCode, Map.of(), body);
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }

    /** 헤더 이름은 대소문자를 구분하지 않고 찾는다. */
    public Optional<String> header(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String target = name.toLowerCase(Locale.ROOT);
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(target))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
