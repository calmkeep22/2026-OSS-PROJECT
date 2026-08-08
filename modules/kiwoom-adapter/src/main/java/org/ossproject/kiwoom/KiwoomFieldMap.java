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
