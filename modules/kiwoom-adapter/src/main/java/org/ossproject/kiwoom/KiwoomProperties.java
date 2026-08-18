package org.ossproject.kiwoom;

import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.OrderType;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 키움 어댑터 설정.
 *
 * <p>이 프로젝트는 증권사 스펙에 코드가 직접 묶이지 않도록, 주소·경로·필드명·코드값을
 * 모두 설정으로 뺐다. 공식 문서를 확인한 뒤 이 객체 하나만 채우면 어댑터 코드는 그대로
 * 동작한다.
 *
 * <p>실거래는 기본적으로 꺼져 있다. {@code paperTrading} 이 참이면 모의투자 서버로만
 * 요청을 보낸다.
 *
 * @param restBaseUrl      REST 기본 주소
 * @param webSocketUrl     실시간 스트림 주소
 * @param endpoints        엔드포인트 경로
 * @param fields           JSON 필드 대응표
 * @param orderStatusCodes 증권사 주문 상태 코드 → 도메인 상태
 * @param orderSideCodes   도메인 매수·매도 → 증권사 코드
 * @param orderTypeCodes   도메인 주문 유형 → 증권사 코드
 * @param requestTimeout   단일 요청 제한 시간
 * @param paperTrading     모의투자 여부. 기본값은 참
 */
public record KiwoomProperties(
        URI restBaseUrl,
        URI webSocketUrl,
        KiwoomEndpoints endpoints,
        KiwoomFieldMap fields,
        Map<String, OrderStatus> orderStatusCodes,
        Map<OrderSide, String> orderSideCodes,
        Map<OrderType, String> orderTypeCodes,
        Duration requestTimeout,
        boolean paperTrading
) {
    public KiwoomProperties {
        if (restBaseUrl == null) {
            throw new IllegalArgumentException("REST 주소는 필수입니다.");
        }
        if (webSocketUrl == null) {
            throw new IllegalArgumentException("WebSocket 주소는 필수입니다.");
        }
        if (endpoints == null) {
            throw new IllegalArgumentException("엔드포인트 설정은 필수입니다.");
        }
        if (fields == null) {
            throw new IllegalArgumentException("필드 대응표는 필수입니다.");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("요청 제한 시간은 0보다 커야 합니다.");
        }
        orderStatusCodes = Map.copyOf(orderStatusCodes == null ? Map.of() : orderStatusCodes);
        orderSideCodes = Map.copyOf(orderSideCodes == null ? Map.of() : orderSideCodes);
        orderTypeCodes = Map.copyOf(orderTypeCodes == null ? Map.of() : orderTypeCodes);
    }

    /**
     * 자리표시자 설정.
     *
     * <p>주소와 경로, 필드명, 코드값이 모두 자리표시자다. 실제 서버에 붙이기 전에
     * 공식 문서를 보고 교체해야 한다.
     */
    public static KiwoomProperties placeholder(URI restBaseUrl, URI webSocketUrl) {
        Map<String, OrderStatus> statusCodes = new LinkedHashMap<>();
        statusCodes.put("accepted", OrderStatus.ACCEPTED);
        statusCodes.put("partially_filled", OrderStatus.PARTIALLY_FILLED);
        statusCodes.put("filled", OrderStatus.FILLED);
        statusCodes.put("cancelled", OrderStatus.CANCELLED);
        statusCodes.put("rejected", OrderStatus.REJECTED);

        return new KiwoomProperties(
                restBaseUrl,
                webSocketUrl,
                KiwoomEndpoints.placeholder(),
                KiwoomFieldMap.placeholder(),
                statusCodes,
                Map.of(OrderSide.BUY, "buy", OrderSide.SELL, "sell"),
                Map.of(OrderType.LIMIT, "limit", OrderType.MARKET, "market"),
                Duration.ofSeconds(10),
                true);
    }

    /** 경로의 치환자를 채워 완전한 주소를 만든다. */
    /**
     * 실제 키움 서버 설정. 자리표시자가 아니라 검증된 주소·경로·필드명을 쓴다.
     *
     * <p>주소는 키움 공식 저장소의 {@code .env.example}, 경로와 {@code api-id} 는 공식
     * 예제 헤더, 필드명은 공식 예제의 응답 매핑에서 확인한 값이다.
     */
    public static KiwoomProperties forEnvironment(KiwoomEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("접속 환경은 필수입니다.");
        }
        Map<String, OrderStatus> statusCodes = new LinkedHashMap<>();
        statusCodes.put("접수", OrderStatus.ACCEPTED);
        statusCodes.put("확인", OrderStatus.ACCEPTED);
        statusCodes.put("체결", OrderStatus.FILLED);
        statusCodes.put("부분체결", OrderStatus.PARTIALLY_FILLED);
        statusCodes.put("취소", OrderStatus.CANCELLED);
        statusCodes.put("거부", OrderStatus.REJECTED);

        return new KiwoomProperties(
                environment.restBaseUrl(),
                environment.webSocketUrl(),
                KiwoomEndpoints.kiwoom(),
                KiwoomFieldMap.kiwoom(),
                statusCodes,
                Map.of(OrderSide.BUY, "2", OrderSide.SELL, "1"),
                Map.of(OrderType.LIMIT, "0", OrderType.MARKET, "3"),
                Duration.ofSeconds(10),
                environment.isPaperTrading());
    }

    /** WebSocket 주소만 바꾼 사본. 접속 경로를 덧붙일 때 쓴다. */
    public KiwoomProperties withWebSocketUrl(URI newWebSocketUrl) {
        return new KiwoomProperties(restBaseUrl, newWebSocketUrl, endpoints, fields,
                orderStatusCodes, orderSideCodes, orderTypeCodes, requestTimeout, paperTrading);
    }

    public URI resolve(String path, Map<String, String> pathVariables) {
        String resolved = path;
        if (pathVariables != null) {
            for (Map.Entry<String, String> entry : pathVariables.entrySet()) {
                resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        String base = restBaseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + resolved);
    }

    public URI resolve(String path) {
        return resolve(path, Map.of());
    }

    /** 증권사 상태 코드를 도메인 상태로 옮긴다. 모르는 코드는 비어 있는 값으로 처리한다. */
    public OrderStatus statusOf(String code) {
        if (code == null) {
            return null;
        }
        return orderStatusCodes.get(code.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
