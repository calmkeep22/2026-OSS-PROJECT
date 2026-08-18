package org.ossproject.kiwoom;

/**
 * 키움 REST API 목록.
 *
 * <p>키움은 모든 조회·주문을 <b>POST</b> 로 보내고, 어떤 기능인지는 URL 이 아니라
 * {@code api-id} 헤더로 구분한다. 같은 경로에 서로 다른 api-id 를 보내는 구조라
 * 경로만으로는 기능을 알 수 없다. 그래서 둘을 한 쌍으로 묶어 둔다.
 *
 * <p>값은 키움 공식 저장소의 예제 헤더에서 확인한 것이다.
 */
public enum KiwoomApi {

    /** 접근 토큰 발급. 유일하게 인증 헤더가 필요 없다. */
    TOKEN("", "/oauth2/token", "접근 토큰 발급"),

    /** 주식호가요청. 매도·매수 10단계 호가와 잔량. */
    ORDER_BOOK("ka10004", "/api/dostk/mrkcond", "주식호가요청"),

    /** 주식기본정보요청. 현재가와 종목 정보. */
    STOCK_INFO("ka10001", "/api/dostk/stkinfo", "주식기본정보요청"),

    /** 주식일봉차트조회요청. */
    DAILY_CHART("ka10081", "/api/dostk/chart", "주식일봉차트조회요청"),

    /** 주식분봉차트조회요청. */
    MINUTE_CHART("ka10080", "/api/dostk/chart", "주식분봉차트조회요청"),

    /** 예수금상세현황요청. */
    DEPOSIT("kt00001", "/api/dostk/acnt", "예수금상세현황요청"),

    /** 계좌평가잔고내역요청. 보유 종목과 평가 손익. */
    BALANCE("kt00018", "/api/dostk/acnt", "계좌평가잔고내역요청"),

    /** 미체결요청. 당일 미체결 주문. */
    UNFILLED_ORDERS("ka10075", "/api/dostk/acnt", "미체결요청"),

    /** 주식 매수주문. */
    BUY_ORDER("kt10000", "/api/dostk/ordr", "주식 매수주문"),

    /** 주식 매도주문. */
    SELL_ORDER("kt10001", "/api/dostk/ordr", "주식 매도주문"),

    /** 주식 취소주문. */
    CANCEL_ORDER("kt10003", "/api/dostk/ordr", "주식 취소주문"),

    /** 실시간 시세 WebSocket. REST 가 아니라 접속 경로다. */
    WEBSOCKET("", "/api/dostk/websocket", "실시간 시세");

    private final String apiId;
    private final String path;
    private final String displayName;

    KiwoomApi(String apiId, String path, String displayName) {
        this.apiId = apiId;
        this.path = path;
        this.displayName = displayName;
    }

    /** {@code api-id} 헤더 값. 토큰 발급과 WebSocket 은 비어 있다. */
    public String apiId() {
        return apiId;
    }

    public boolean hasApiId() {
        return !apiId.isEmpty();
    }

    public String path() {
        return path;
    }

    /** 화면과 오류 메시지에 쓸 한국어 이름. */
    public String displayName() {
        return displayName;
    }
}
