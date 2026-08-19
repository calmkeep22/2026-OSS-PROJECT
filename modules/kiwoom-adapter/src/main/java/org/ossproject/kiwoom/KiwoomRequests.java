package org.ossproject.kiwoom;

import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderType;

/**
 * 키움 REST 요청 본문을 만든다.
 *
 * <p>필드 이름과 코드값은 키움 공식 저장소 예제의 함수 시그니처와 주석에서 확인한 것이다.
 *
 * <p>주의할 점이 하나 있다. <b>매수와 매도는 본문 필드가 아니라 서로 다른 API</b>다.
 * {@code kt10000} 이 매수, {@code kt10001} 이 매도이며, 본문의 {@code trde_tp} 는
 * 매매 구분이 아니라 <b>주문 유형</b>(지정가·시장가)이다. 이 둘을 혼동하면 매도하려다
 * 매수 주문이 나가므로, 주문 API 선택은 {@link #orderApi(OrderSide)} 한 곳에서만 한다.
 */
public final class KiwoomRequests {

    /** 국내거래소구분. KRX, NXT, SOR 중 하나. */
    public static final String EXCHANGE_KRX = "KRX";

    /** 매매구분 코드. 0 보통(지정가), 3 시장가. */
    private static final String TRADE_TYPE_LIMIT = "0";
    private static final String TRADE_TYPE_MARKET = "3";

    private KiwoomRequests() {
    }

    /**
     * 주문 방향에 맞는 API 를 고른다.
     *
     * <p>이 프로젝트에서 매수·매도를 가르는 지점은 여기 하나뿐이다.
     */
    public static KiwoomApi orderApi(OrderSide side) {
        if (side == null) {
            throw new IllegalArgumentException("주문 구분은 필수입니다.");
        }
        return side == OrderSide.BUY ? KiwoomApi.BUY_ORDER : KiwoomApi.SELL_ORDER;
    }

    /** 매매구분 코드. */
    public static String tradeType(OrderType type) {
        if (type == null) {
            throw new IllegalArgumentException("주문 유형은 필수입니다.");
        }
        return type == OrderType.MARKET ? TRADE_TYPE_MARKET : TRADE_TYPE_LIMIT;
    }

    /** 호가 조회(ka10004)와 기본정보 조회(ka10001) 요청. */
    public static String bySymbol(String symbol) {
        return "{\"stk_cd\":" + quote(symbol) + "}";
    }

    /**
     * 일봉 조회(ka10081) 요청.
     *
     * @param baseDate     기준일자 {@code yyyyMMdd}
     * @param adjustPrices 수정주가 반영 여부
     */
    public static String dailyChart(String symbol, String baseDate, boolean adjustPrices) {
        return "{\"stk_cd\":" + quote(symbol)
                + ",\"base_dt\":" + quote(baseDate)
                + ",\"upd_stkpc_tp\":" + quote(adjustPrices ? "1" : "0") + "}";
    }

    /**
     * 계좌평가잔고내역(kt00018) 요청.
     *
     * @param singleAccount 참이면 단일 계좌, 거짓이면 통합
     */
    public static String balance(boolean singleAccount) {
        return "{\"qry_tp\":" + quote(singleAccount ? "1" : "2")
                + ",\"dmst_stex_tp\":" + quote(EXCHANGE_KRX) + "}";
    }

    /** 예수금상세현황(kt00001) 요청. */
    public static String deposit() {
        return "{\"qry_tp\":" + quote("2") + "}";
    }

    /**
     * 미체결 조회(ka10075) 요청.
     *
     * @param symbol 특정 종목만 볼 때. 비어 있으면 전체
     */
    public static String unfilledOrders(String symbol) {
        StringBuilder sb = new StringBuilder("{")
                .append("\"all_stk_tp\":").append(quote(symbol == null || symbol.isBlank() ? "0" : "1"))
                .append(",\"trde_tp\":").append(quote("0"))
                .append(",\"stex_tp\":").append(quote("0"));
        if (symbol != null && !symbol.isBlank()) {
            sb.append(",\"stk_cd\":").append(quote(symbol));
        }
        return sb.append('}').toString();
    }

    /**
     * 주문 접수(kt10000 매수 / kt10001 매도) 요청.
     *
     * <p>시장가면 주문단가를 비워 보낸다. 값을 넣으면 거부되거나 지정가로 처리될 수 있다.
     */
    public static String placeOrder(OrderCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        String unitPrice = command.type() == OrderType.LIMIT
                ? command.limitPrice().stripTrailingZeros().toPlainString()
                : "";
        return "{\"dmst_stex_tp\":" + quote(EXCHANGE_KRX)
                + ",\"stk_cd\":" + quote(command.symbol())
                + ",\"ord_qty\":" + quote(Long.toString(command.quantity()))
                + ",\"trde_tp\":" + quote(tradeType(command.type()))
                + ",\"ord_uv\":" + quote(unitPrice)
                + ",\"cond_uv\":" + quote("") + "}";
    }

    /** 주문 취소(kt10003) 요청. 취소 수량이 0이면 잔량 전부를 취소한다. */
    public static String cancelOrder(String originalOrderId, String symbol, long cancelQuantity) {
        if (originalOrderId == null || originalOrderId.isBlank()) {
            throw new IllegalArgumentException("원주문번호는 필수입니다.");
        }
        return "{\"dmst_stex_tp\":" + quote(EXCHANGE_KRX)
                + ",\"orig_ord_no\":" + quote(originalOrderId)
                + ",\"stk_cd\":" + quote(symbol == null ? "" : symbol)
                + ",\"cncl_qty\":" + quote(cancelQuantity <= 0 ? "0" : Long.toString(cancelQuantity)) + "}";
    }

    private static String quote(String value) {
        String escaped = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
