package org.ossproject.kiwoom;

import java.net.URI;
import java.time.Duration;

/**
 * 키움 어댑터 설정.
 *
 * <p>REST 경로와 필드명은 더 이상 설정으로 두지 않는다. 공식 문서를 확인한 결과 키움은 TR 코드마다
 * 요청 본문과 응답 구조가 다르기 때문에, 이름만 바꿔 끼우는 방식으로는 표현할 수 없다. TR 목록은
 * {@link KiwoomTr} 에, 응답 해석은 {@link KiwoomJsonMapper} 에 명시한다.
 *
 * <p>{@code fields} 는 아직 실시간 시세 스트림이 사용한다. WebSocket 실시간 규격을 확인한 뒤
 * 함께 정리한다.
 *
 * @param restBaseUrl    REST 기본 주소
 * @param webSocketUrl   실시간 스트림 주소
 * @param fields         실시간 스트림이 쓰는 JSON 필드 대응표
 * @param requestTimeout 단일 요청 제한 시간
 * @param paperTrading   모의투자 여부. 기본값은 참
 * @param exchange       국내거래소구분. {@code KRX}, {@code NXT}, {@code SOR}
 * @param adjustedPrice  수정주가 적용 여부. 차트 조회의 {@code upd_stkpc_tp}
 */
public record KiwoomProperties(
        URI restBaseUrl,
        URI webSocketUrl,
        KiwoomFieldMap fields,
        Duration requestTimeout,
        boolean paperTrading,
        String exchange,
        boolean adjustedPrice
) {
    /** 모의투자 REST 주소. */
    public static final URI MOCK_REST = URI.create("https://mockapi.kiwoom.com");
    /** 실전 REST 주소. */
    public static final URI LIVE_REST = URI.create("https://api.kiwoom.com");

    public KiwoomProperties {
        if (restBaseUrl == null) {
            throw new IllegalArgumentException("REST 주소는 필수입니다.");
        }
        if (webSocketUrl == null) {
            throw new IllegalArgumentException("WebSocket 주소는 필수입니다.");
        }
        if (fields == null) {
            throw new IllegalArgumentException("필드 대응표는 필수입니다.");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("요청 제한 시간은 0보다 커야 합니다.");
        }
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalArgumentException("국내거래소구분은 필수입니다.");
        }
        exchange = exchange.trim().toUpperCase(java.util.Locale.ROOT);
        if (!exchange.equals("KRX") && !exchange.equals("NXT") && !exchange.equals("SOR")) {
            throw new IllegalArgumentException(
                    "국내거래소구분은 KRX, NXT, SOR 중 하나여야 합니다. 입력값 " + exchange);
        }
    }

    /** 모의투자 기본 설정. */
    public static KiwoomProperties mockTrading(URI webSocketUrl) {
        return new KiwoomProperties(MOCK_REST, webSocketUrl, KiwoomFieldMap.placeholder(),
                Duration.ofSeconds(10), true, "KRX", true);
    }

    /** 실전 기본 설정. 실거래 전송은 별도의 실행 인자로 한 번 더 막혀 있다. */
    public static KiwoomProperties liveTrading(URI webSocketUrl) {
        return new KiwoomProperties(LIVE_REST, webSocketUrl, KiwoomFieldMap.placeholder(),
                Duration.ofSeconds(10), false, "KRX", true);
    }

    /** TR 경로를 붙인 완전한 주소. */
    public URI resolve(KiwoomTr tr) {
        if (tr == null) {
            throw new IllegalArgumentException("TR 은 필수입니다.");
        }
        String base = restBaseUrl.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + tr.path());
    }

    /** 차트 조회의 {@code upd_stkpc_tp} 값. */
    public String adjustedPriceCode() {
        return adjustedPrice ? "1" : "0";
    }
}
