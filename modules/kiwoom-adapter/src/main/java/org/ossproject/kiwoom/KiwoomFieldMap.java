package org.ossproject.kiwoom;

import java.util.EnumMap;
import java.util.Map;

/**
 * 논리 필드 이름과 실제 JSON 필드 이름의 대응표.
 *
 * <p><b>중요:</b> {@link #placeholder()} 의 값은 검증된 스펙이 아니라 자리표시자다.
 * 실계좌나 모의투자 서버에 붙이기 전에 반드시 키움 공식 문서를 보고
 * {@link Builder} 로 실제 필드 이름을 채워야 한다. 값이 틀리면 파싱 단계에서
 * {@link org.ossproject.broker.BrokerException} 이 발생하므로, 잘못된 값이 조용히
 * 통과하지는 않는다.
 *
 * <p>필드 이름만 갈아 끼우면 되도록 설계했기 때문에, 스펙 확인은 이 클래스 하나만
 * 고치는 작업으로 끝난다.
 */
public final class KiwoomFieldMap {

    private final Map<KiwoomField, String> names;

    private KiwoomFieldMap(Map<KiwoomField, String> names) {
        this.names = new EnumMap<>(names);
    }

    /**
     * 실제 키움 응답 필드명.
     *
     * <p>토큰 응답은 OAuth2 관례와 달리 {@code access_token} 이 아니라 {@code token} 이고,
     * 만료도 {@code expires_in}(남은 초) 이 아니라 {@code expires_dt}(만료 시각) 이다.
     * 이 차이를 놓치면 토큰이 매번 재발급되거나 만료를 놓친다.
     *
     * <p>호가 필드는 {@link KiwoomOrderBookParser} 가 규칙으로 직접 다루므로 여기 없다.
     */
    public static KiwoomFieldMap kiwoom() {
        return builder()
                .map(KiwoomField.TOKEN_VALUE, "token")
                .map(KiwoomField.TOKEN_EXPIRES_IN, "expires_dt")

                .map(KiwoomField.QUOTE_SYMBOL, "stk_cd")
                .map(KiwoomField.QUOTE_PRICE, "cur_prc")
                .map(KiwoomField.QUOTE_PREVIOUS_CLOSE, "base_pric")
                .map(KiwoomField.QUOTE_BID_PRICE, "buy_fpr_bid")
                .map(KiwoomField.QUOTE_ASK_PRICE, "sel_fpr_bid")
                .map(KiwoomField.QUOTE_VOLUME, "trde_qty")

                .map(KiwoomField.CANDLE_LIST, "stk_dt_pole_chart_qry")
                .map(KiwoomField.CANDLE_DATE, "dt")
                .map(KiwoomField.CANDLE_TIME, "cntr_tm")
                .map(KiwoomField.CANDLE_OPEN, "open_pric")
                .map(KiwoomField.CANDLE_HIGH, "high_pric")
                .map(KiwoomField.CANDLE_LOW, "low_pric")
                .map(KiwoomField.CANDLE_CLOSE, "cur_prc")
                .map(KiwoomField.CANDLE_VOLUME, "trde_qty")

                .map(KiwoomField.ACCOUNT_CASH, "entr")
                .map(KiwoomField.ACCOUNT_SETTLED_CASH, "d2_entra")
                .map(KiwoomField.ACCOUNT_ORDERABLE, "ord_alow_amt")
                .map(KiwoomField.ACCOUNT_WITHDRAWABLE, "wthd_alowa")
                .map(KiwoomField.ACCOUNT_ESTIMATED_ASSETS, "prsm_dpst_aset_amt")
                .map(KiwoomField.ACCOUNT_POSITIONS, "acnt_evlt_remn_indv_tot")
                .map(KiwoomField.POSITION_SYMBOL, "stk_cd")
                .map(KiwoomField.POSITION_NAME, "stk_nm")
                .map(KiwoomField.POSITION_QUANTITY, "rmnd_qty")
                .map(KiwoomField.POSITION_AVERAGE_PRICE, "pur_pric")
                .map(KiwoomField.POSITION_CURRENT_PRICE, "cur_prc")

                .map(KiwoomField.ORDER_LIST, "oso")
                .map(KiwoomField.ORDER_ID, "ord_no")
                .map(KiwoomField.ORDER_SYMBOL, "stk_cd")
                .map(KiwoomField.ORDER_NAME, "stk_nm")
                .map(KiwoomField.ORDER_SIDE, "trde_tp")
                .map(KiwoomField.ORDER_TYPE, "io_tp_nm")
                .map(KiwoomField.ORDER_QUANTITY, "ord_qty")
                .map(KiwoomField.ORDER_FILLED_QUANTITY, "cntr_qty")
                .map(KiwoomField.ORDER_PRICE, "ord_pric")
                .map(KiwoomField.ORDER_AVERAGE_FILLED_PRICE, "cntr_pric")
                .map(KiwoomField.ORDER_STATUS, "ord_stt")
                .build();
    }

    /**
     * 자리표시자 대응표. 실제 스펙으로 교체하기 전까지는 테스트와 개발에만 쓴다.
     */
    public static KiwoomFieldMap placeholder() {
        return builder()
                .map(KiwoomField.TOKEN_VALUE, "access_token")
                .map(KiwoomField.TOKEN_EXPIRES_IN, "expires_in")

                .map(KiwoomField.QUOTE_SYMBOL, "symbol")
                .map(KiwoomField.QUOTE_PRICE, "price")
                .map(KiwoomField.QUOTE_PREVIOUS_CLOSE, "previous_close")
                .map(KiwoomField.QUOTE_BID_PRICE, "bid_price")
                .map(KiwoomField.QUOTE_ASK_PRICE, "ask_price")
                .map(KiwoomField.QUOTE_BID_SIZE, "bid_size")
                .map(KiwoomField.QUOTE_ASK_SIZE, "ask_size")
                .map(KiwoomField.QUOTE_VOLUME, "volume")

                .map(KiwoomField.CANDLE_LIST, "candles")
                .map(KiwoomField.CANDLE_DATE, "date")
                .map(KiwoomField.CANDLE_TIME, "time")
                .map(KiwoomField.CANDLE_OPEN, "open")
                .map(KiwoomField.CANDLE_HIGH, "high")
                .map(KiwoomField.CANDLE_LOW, "low")
                .map(KiwoomField.CANDLE_CLOSE, "close")
                .map(KiwoomField.CANDLE_VOLUME, "volume")

                .map(KiwoomField.ACCOUNT_CASH, "cash")
                .map(KiwoomField.ACCOUNT_SETTLED_CASH, "settledCash")
                .map(KiwoomField.ACCOUNT_ORDERABLE, "orderable")
                .map(KiwoomField.ACCOUNT_WITHDRAWABLE, "withdrawable")
                .map(KiwoomField.ACCOUNT_ESTIMATED_ASSETS, "estimatedAssets")
                .map(KiwoomField.ACCOUNT_POSITIONS, "positions")
                .map(KiwoomField.POSITION_SYMBOL, "symbol")
                .map(KiwoomField.POSITION_NAME, "name")
                .map(KiwoomField.POSITION_QUANTITY, "quantity")
                .map(KiwoomField.POSITION_AVERAGE_PRICE, "average_price")
                .map(KiwoomField.POSITION_CURRENT_PRICE, "current_price")

                .map(KiwoomField.ORDER_LIST, "orders")
                .map(KiwoomField.ORDER_ID, "order_id")
                .map(KiwoomField.ORDER_SYMBOL, "symbol")
                .map(KiwoomField.ORDER_NAME, "name")
                .map(KiwoomField.ORDER_SIDE, "side")
                .map(KiwoomField.ORDER_TYPE, "order_type")
                .map(KiwoomField.ORDER_QUANTITY, "quantity")
                .map(KiwoomField.ORDER_FILLED_QUANTITY, "filled_quantity")
                .map(KiwoomField.ORDER_PRICE, "price")
                .map(KiwoomField.ORDER_AVERAGE_FILLED_PRICE, "average_filled_price")
                .map(KiwoomField.ORDER_STATUS, "status")
                .build();
    }

    /**
     * 논리 필드에 대응하는 JSON 필드 이름.
     *
     * @throws IllegalStateException 대응이 정의되지 않은 경우
     */
    public String nameOf(KiwoomField field) {
        String name = names.get(field);
        if (name == null) {
            throw new IllegalStateException(
                    "JSON 필드 이름이 설정되지 않았습니다. " + field
                            + " 항목을 키움 공식 문서에 맞춰 채워야 합니다.");
        }
        return name;
    }

    public boolean has(KiwoomField field) {
        return names.containsKey(field);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 기존 대응표에서 일부만 바꿀 때 쓴다. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.names.putAll(names);
        return builder;
    }

    /** 대응표 빌더. */
    public static final class Builder {
        private final Map<KiwoomField, String> names = new EnumMap<>(KiwoomField.class);

        public Builder map(KiwoomField field, String jsonName) {
            if (field == null) {
                throw new IllegalArgumentException("필드는 필수입니다.");
            }
            if (jsonName == null || jsonName.isBlank()) {
                throw new IllegalArgumentException("JSON 필드 이름은 비어 있을 수 없습니다.");
            }
            names.put(field, jsonName);
            return this;
        }

        public KiwoomFieldMap build() {
            return new KiwoomFieldMap(names);
        }
    }
}
