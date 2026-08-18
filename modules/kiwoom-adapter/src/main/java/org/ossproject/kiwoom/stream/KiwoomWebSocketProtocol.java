package org.ossproject.kiwoom.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.finance.model.Quote;
import org.ossproject.kiwoom.KiwoomOrderBookParser;

import java.math.BigDecimal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 키움 실시간 WebSocket 메시지 규격.
 *
 * <p>흐름은 이렇다.
 * <ol>
 *   <li>연결 직후 {@code LOGIN} 패킷에 접근 토큰을 담아 보낸다.</li>
 *   <li>로그인이 성공하면 {@code REG} 패킷으로 종목과 실시간 종류를 등록한다.</li>
 *   <li>서버가 {@code PING} 을 보내면 <b>받은 패킷을 그대로 되돌려보낸다.</b>
 *       응답하지 않으면 서버가 연결을 끊는다.</li>
 *   <li>시세는 {@code REAL} 메시지로 오며, 값은 FID 번호를 키로 하는 맵이다.</li>
 * </ol>
 *
 * <p>메시지 형식은 키움 공식 저장소의 패킷 빌더와 WebSocket 클라이언트에서 확인한 것이다.
 */
public final class KiwoomWebSocketProtocol {

    /** 구독 그룹 번호. 하나만 쓰면 충분하다. */
    private static final String GROUP_NO = "1";

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public KiwoomWebSocketProtocol(ObjectMapper objectMapper, Clock clock) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper 는 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 보내는 메시지
    // ------------------------------------------------------------------

    /** 연결 직후 보내는 로그인 패킷. */
    public String loginMessage(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("접근 토큰은 필수입니다.");
        }
        return "{\"trnm\":\"LOGIN\",\"token\":\"" + escape(accessToken) + "\"}";
    }

    /**
     * 실시간 등록 패킷.
     *
     * @param keepExisting 참이면 기존 등록을 유지한 채 추가한다. 거짓이면 기존 등록을 대체한다.
     *                     재연결 후 구독을 복원할 때는 대체가 맞다. 이미 서버 쪽 등록이
     *                     사라졌는데 유지 옵션으로 보내면 중복이 쌓일 수 있다.
     */
    public String registerMessage(Collection<String> symbols,
                                  Collection<KiwoomRealtimeType> types,
                                  boolean keepExisting) {
        return controlMessage("REG", symbols, types, keepExisting ? "1" : "0");
    }

    /** 실시간 해제 패킷. */
    public String removeMessage(Collection<String> symbols, Collection<KiwoomRealtimeType> types) {
        return controlMessage("REMOVE", symbols, types, null);
    }

    private String controlMessage(String trnm, Collection<String> symbols,
                                  Collection<KiwoomRealtimeType> types, String refresh) {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("종목은 최소 하나가 필요합니다.");
        }
        if (types == null || types.isEmpty()) {
            throw new IllegalArgumentException("실시간 종류는 최소 하나가 필요합니다.");
        }
        String items = symbols.stream()
                .map(symbol -> "\"" + escape(symbol) + "\"")
                .collect(Collectors.joining(","));
        String typeCodes = types.stream()
                .map(type -> "\"" + type.code() + "\"")
                .collect(Collectors.joining(","));

        StringBuilder sb = new StringBuilder("{\"trnm\":\"").append(trnm)
                .append("\",\"grp_no\":\"").append(GROUP_NO).append('"');
        if (refresh != null) {
            sb.append(",\"refresh\":\"").append(refresh).append('"');
        }
        sb.append(",\"data\":[{\"item\":[").append(items)
                .append("],\"type\":[").append(typeCodes).append("]}]}");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 받는 메시지
    // ------------------------------------------------------------------

    /**
     * 수신 메시지를 해석한다.
     *
     * <p>해석할 수 없는 메시지는 예외를 던지지 않고 {@link KiwoomStreamEvent.Ignored} 로
     * 돌려준다. 알 수 없는 메시지 하나 때문에 연결이 끊기면 안 되기 때문이다.
     */
    public List<KiwoomStreamEvent> decode(String message) {
        if (message == null || message.isBlank()) {
            return List.of(new KiwoomStreamEvent.Ignored("빈 메시지"));
        }

        // 서버가 순수 문자열 PING 을 보내는 경우도 있다.
        if ("PING".equalsIgnoreCase(message.trim())) {
            return List.of(new KiwoomStreamEvent.Ping(message));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(message);
        } catch (Exception e) {
            return List.of(new KiwoomStreamEvent.Ignored("JSON 이 아님"));
        }
        if (!root.isObject()) {
            return List.of(new KiwoomStreamEvent.Ignored("객체가 아님"));
        }

        String trnm = text(root, "trnm");
        if (trnm == null) {
            return List.of(new KiwoomStreamEvent.Ignored("trnm 없음"));
        }

        switch (trnm.toUpperCase(java.util.Locale.ROOT)) {
            case "PING":
                return List.of(new KiwoomStreamEvent.Ping(message));
            case "LOGIN":
                return List.of(decodeLogin(root));
            case "REAL":
                return decodeReal(root);
            default:
                return List.of(new KiwoomStreamEvent.Ignored(trnm));
        }
    }

    private KiwoomStreamEvent decodeLogin(JsonNode root) {
        JsonNode codeNode = root.get("return_code");
        int returnCode = codeNode == null || codeNode.isNull() ? 0 : codeNode.asInt();
        String message = text(root, "return_msg");
        boolean success = returnCode == 0;
        return new KiwoomStreamEvent.LoginResult(success, returnCode,
                message == null ? (success ? "실시간 로그인에 성공했습니다." : "실시간 로그인에 실패했습니다.") : message);
    }

    /**
     * REAL 메시지를 해석한다.
     *
     * <p>한 메시지에 여러 종목·여러 종류가 함께 올 수 있다. 첫 항목만 처리하면 같이 온
     * 체결이나 다른 종목의 호가를 조용히 버리게 되므로 전부 사건으로 만들어 돌려준다.
     */
    private List<KiwoomStreamEvent> decodeReal(JsonNode root) {
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            return List.of(new KiwoomStreamEvent.Ignored("data 없음"));
        }
        List<KiwoomStreamEvent> events = new ArrayList<>();
        for (JsonNode entry : data) {
            KiwoomRealtimeType type = KiwoomRealtimeType.fromCode(text(entry, "type"));
            String symbol = text(entry, "item");
            JsonNode values = entry.get("values");
            if (type == null || symbol == null || values == null || !values.isObject()) {
                continue;
            }
            if (type == KiwoomRealtimeType.ORDER_BOOK) {
                events.add(new KiwoomStreamEvent.OrderBookUpdate(
                        KiwoomOrderBookParser.fromRealtime(symbol, values, clock.instant())));
            } else if (type == KiwoomRealtimeType.TRADE) {
                Quote quote = toQuote(symbol, values);
                if (quote != null) {
                    events.add(new KiwoomStreamEvent.QuoteUpdate(quote));
                }
            }
        }
        if (events.isEmpty()) {
            return List.of(new KiwoomStreamEvent.Ignored("처리 대상 실시간 종류 없음"));
        }
        return List.copyOf(events);
    }

    /**
     * 주식체결(0B) 값을 현재가로 옮긴다.
     *
     * <p>FID 10 현재가, 13 누적거래량, 27·28 최우선 매도·매수 호가.
     * 키움은 하락 시 가격에 음수 부호를 붙여 보내므로 절대값을 취한다.
     */
    private Quote toQuote(String symbol, JsonNode values) {
        BigDecimal price = decimal(values, "10");
        if (price == null) {
            return null;
        }
        return new Quote(symbol, price, null,
                decimal(values, "28"), decimal(values, "27"),
                0L, 0L, longValue(values, "13"), clock.instant());
    }

    private static BigDecimal decimal(JsonNode parent, String fid) {
        String raw = numeric(parent, fid);
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw).abs();
            return value.signum() == 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longValue(JsonNode parent, String fid) {
        String raw = numeric(parent, fid);
        if (raw == null) {
            return 0L;
        }
        try {
            return new BigDecimal(raw).abs().longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String numeric(JsonNode parent, String fid) {
        String raw = text(parent, fid);
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(",", "").replace("+", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
