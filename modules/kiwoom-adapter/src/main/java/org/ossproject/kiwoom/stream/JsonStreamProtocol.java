package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.KiwoomField;
import org.ossproject.kiwoom.KiwoomJsonMapper;
import org.ossproject.kiwoom.KiwoomProperties;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JSON 기반 기본 프로토콜.
 *
 * <p>구독 메시지는 {@code {"type":"subscribe","symbols":["005930"]}} 형태로 보낸다.
 * 이는 자리표시자이며, 키움 공식 문서에 맞춰 이 클래스만 교체하거나 수정하면 된다.
 *
 * <p>수신 메시지는 {@link KiwoomFieldMap} 에 정의된 시세 필드를 가진 JSON 객체로 본다.
 * 해석할 수 없는 메시지는 무시한다. 하트비트나 구독 응답 때문에 스트림이 끊기면 안 되기
 * 때문이다.
 */
final class JsonStreamProtocol implements StreamProtocol {

    private final KiwoomJsonMapper jsonMapper;
    private final KiwoomProperties properties;

    public JsonStreamProtocol(KiwoomJsonMapper jsonMapper, KiwoomProperties properties) {
        if (jsonMapper == null) {
            throw new IllegalArgumentException("JSON 매퍼는 필수입니다.");
        }
        if (properties == null) {
            throw new IllegalArgumentException("설정은 필수입니다.");
        }
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    @Override
    public String subscribeMessage(Collection<String> symbols) {
        return message("subscribe", symbols);
    }

    @Override
    public String unsubscribeMessage(Collection<String> symbols) {
        return message("unsubscribe", symbols);
    }

    @Override
    public Optional<Quote> parseQuote(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = jsonMapper.parse(message);
            if (!node.isObject()) {
                return Optional.empty();
            }
            // 시세 메시지인지 최소 조건으로 판별한다.
            String symbolField = properties.fields().nameOf(KiwoomField.QUOTE_SYMBOL);
            String priceField = properties.fields().nameOf(KiwoomField.QUOTE_PRICE);
            if (!node.hasNonNull(symbolField) || !node.hasNonNull(priceField)) {
                return Optional.empty();
            }
            return Optional.of(jsonMapper.toQuote(node));
        } catch (RuntimeException e) {
            // 해석 실패로 스트림을 끊지 않는다.
            return Optional.empty();
        }
    }

    private static String message(String type, Collection<String> symbols) {
        String joined = symbols.stream()
                .map(symbol -> "\"" + symbol.replace("\"", "") + "\"")
                .collect(Collectors.joining(","));
        return "{\"type\":\"" + type + "\",\"symbols\":[" + joined + "]}";
    }
}
