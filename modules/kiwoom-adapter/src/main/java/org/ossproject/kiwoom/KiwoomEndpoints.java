package org.ossproject.kiwoom;

/**
 * REST 엔드포인트 경로.
 *
 * <p><b>중요:</b> {@link #placeholder()} 의 경로는 검증된 스펙이 아니라 자리표시자다.
 * 실제 서버에 붙이기 전에 키움 공식 문서의 경로로 교체해야 한다.
 *
 * @param tokenPath       접근 토큰 발급
 * @param quotePath       현재가 조회. {@code {symbol}} 치환자를 쓸 수 있다
 * @param candlePath      봉 조회. {@code {symbol}} 치환자를 쓸 수 있다
 * @param accountPath     계좌 잔고 조회. {@code {accountNo}} 치환자를 쓸 수 있다
 * @param orderPath       주문 접수
 * @param orderCancelPath 주문 취소
 * @param orderListPath   주문 내역 조회. {@code {accountNo}} 치환자를 쓸 수 있다
 */
public record KiwoomEndpoints(
        String tokenPath,
        String quotePath,
        String candlePath,
        String accountPath,
        String orderPath,
        String orderCancelPath,
        String orderListPath
) {
    public KiwoomEndpoints {
        requirePath(tokenPath, "토큰 발급");
        requirePath(quotePath, "현재가 조회");
        requirePath(candlePath, "봉 조회");
        requirePath(accountPath, "잔고 조회");
        requirePath(orderPath, "주문 접수");
        requirePath(orderCancelPath, "주문 취소");
        requirePath(orderListPath, "주문 내역 조회");
    }

    /**
     * 실제 키움 경로.
     *
     * <p>키움은 기능을 URL 이 아니라 {@code api-id} 헤더로 구분하므로 여러 기능이 같은
     * 경로를 공유한다. 기능별 {@code api-id} 는 {@link KiwoomApi} 에 있다.
     */
    public static KiwoomEndpoints kiwoom() {
        return new KiwoomEndpoints(
                KiwoomApi.TOKEN.path(),
                KiwoomApi.ORDER_BOOK.path(),
                KiwoomApi.DAILY_CHART.path(),
                KiwoomApi.BALANCE.path(),
                KiwoomApi.BUY_ORDER.path(),
                KiwoomApi.CANCEL_ORDER.path(),
                KiwoomApi.UNFILLED_ORDERS.path());
    }

    /** 자리표시자. 실제 스펙으로 교체해야 한다. */
    public static KiwoomEndpoints placeholder() {
        return new KiwoomEndpoints(
                "/oauth2/token",
                "/quotes/{symbol}",
                "/candles/{symbol}",
                "/accounts/{accountNo}/balance",
                "/orders",
                "/orders/cancel",
                "/accounts/{accountNo}/orders");
    }

    private static void requirePath(String path, String label) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(label + " 경로는 필수입니다.");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException(label + " 경로는 / 로 시작해야 합니다. 입력값 " + path);
        }
    }
}
