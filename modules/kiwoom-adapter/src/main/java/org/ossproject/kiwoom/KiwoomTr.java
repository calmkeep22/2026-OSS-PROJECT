package org.ossproject.kiwoom;

/**
 * 키움 REST TR.
 *
 * <p>키움은 기능마다 경로를 나누지 않는다. 경로는 업무 카테고리 하나이고, 실제로 어떤 조회인지는
 * {@code api-id} 헤더의 TR 코드가 정한다. 예를 들어 일봉과 분봉은 둘 다 {@code /api/dostk/chart}
 * 로 가고 {@code ka10081} 과 {@code ka10080} 으로 갈린다.
 *
 * <p>모든 TR은 POST 이며 요청과 응답 모두 JSON 이다.
 *
 * <p>각 상수는 {@code api-id} 헤더에 넣는 TR 코드와 업무 카테고리 경로를 함께 갖는다.
 */
public enum KiwoomTr {

    /** 접근토큰 발급. 토큰 없이 호출하는 유일한 TR 이다. */
    ISSUE_TOKEN("au10001", "/oauth2/token"),
    /** 접근토큰 폐기. */
    REVOKE_TOKEN("au10002", "/oauth2/revoke"),

    /** 주식기본정보요청. 현재가·시가·고가·저가·거래량·등락률을 함께 준다. */
    STOCK_BASIC_INFO("ka10001", "/api/dostk/stkinfo"),
    /** 주식호가요청. 매도·매수 최우선 호가와 잔량. */
    ORDER_BOOK("ka10004", "/api/dostk/mrkcond"),
    /** 체결정보요청. 최근 체결 내역. */
    TRADE_HISTORY("ka10003", "/api/dostk/stkinfo"),
    /** 종목정보 리스트. 시장별 종목 코드와 이름. 현재가는 주지 않는다. */
    SECURITY_LIST("ka10099", "/api/dostk/stkinfo"),

    /** 주식분봉차트조회요청. */
    CHART_MINUTE("ka10080", "/api/dostk/chart"),
    /** 주식일봉차트조회요청. */
    CHART_DAY("ka10081", "/api/dostk/chart"),
    /** 주식주봉차트조회요청. */
    CHART_WEEK("ka10082", "/api/dostk/chart"),
    /** 주식월봉차트조회요청. */
    CHART_MONTH("ka10083", "/api/dostk/chart"),

    /** 계좌번호조회. */
    ACCOUNT_NUMBERS("ka00001", "/api/dostk/acnt"),
    /** 예수금상세현황요청. 예수금을 준다. */
    DEPOSIT_DETAIL("kt00001", "/api/dostk/acnt"),
    /** 계좌평가잔고내역요청. 보유 종목을 준다. */
    BALANCE_DETAIL("kt00018", "/api/dostk/acnt"),
    /** 계좌별주문체결현황요청. */
    ORDER_STATUS("kt00009", "/api/dostk/acnt"),

    /** 주식 매수주문. */
    ORDER_BUY("kt10000", "/api/dostk/ordr"),
    /** 주식 매도주문. */
    ORDER_SELL("kt10001", "/api/dostk/ordr"),
    /** 주식 취소주문. */
    ORDER_CANCEL("kt10003", "/api/dostk/ordr");

    private final String id;
    private final String path;

    KiwoomTr(String id, String path) {
        this.id = id;
        this.path = path;
    }

    /** {@code api-id} 헤더 값. */
    public String id() {
        return id;
    }

    public String path() {
        return path;
    }

    /** 봉 주기에 맞는 차트 TR. */
    public static KiwoomTr chartFor(org.ossproject.finance.model.CandleInterval interval) {
        if (interval == null) {
            throw new IllegalArgumentException("봉 주기는 필수입니다.");
        }
        return switch (interval) {
            case MINUTE_1, MINUTE_5, MINUTE_15, MINUTE_60 -> CHART_MINUTE;
            case DAY -> CHART_DAY;
            case WEEK -> CHART_WEEK;
            case MONTH -> CHART_MONTH;
        };
    }

    /**
     * 분봉 TR의 {@code tic_scope} 값.
     *
     * @throws IllegalArgumentException 분봉이 아닌 주기인 경우
     */
    public static String tickScopeOf(org.ossproject.finance.model.CandleInterval interval) {
        return switch (interval) {
            case MINUTE_1 -> "1";
            case MINUTE_5 -> "5";
            case MINUTE_15 -> "15";
            case MINUTE_60 -> "60";
            default -> throw new IllegalArgumentException("분봉이 아닙니다: " + interval);
        };
    }
}
