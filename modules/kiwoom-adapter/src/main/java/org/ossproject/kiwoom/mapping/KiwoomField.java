package org.ossproject.kiwoom.mapping;

/**
 * 키움 응답 JSON에서 읽어야 하는 논리 필드.
 *
 * <p>실제 JSON 필드 이름은 증권사 문서에 따라 달라지므로 {@link KiwoomFieldMap} 에서
 * 이름을 주입한다. 코드가 특정 필드명에 묶이지 않게 하려는 목적이다.
 */
public enum KiwoomField {

    TOKEN_VALUE,
    TOKEN_EXPIRES_IN,

    QUOTE_SYMBOL,
    QUOTE_PRICE,
    QUOTE_PREVIOUS_CLOSE,
    QUOTE_BID_PRICE,
    QUOTE_ASK_PRICE,
    QUOTE_BID_SIZE,
    QUOTE_ASK_SIZE,
    QUOTE_VOLUME,

    /** 봉 배열이 들어 있는 노드 이름. */
    CANDLE_LIST,
    CANDLE_DATE,
    CANDLE_TIME,
    CANDLE_OPEN,
    CANDLE_HIGH,
    CANDLE_LOW,
    CANDLE_CLOSE,
    CANDLE_VOLUME,

    ACCOUNT_CASH,
    /** D+2 추정예수금. 미수면 음수. */
    ACCOUNT_SETTLED_CASH,
    ACCOUNT_ORDERABLE,
    ACCOUNT_WITHDRAWABLE,
    /** 증권사가 계산한 추정예탁자산. 직접 더한 값보다 우선한다. */
    ACCOUNT_ESTIMATED_ASSETS,
    /** 보유 종목 배열이 들어 있는 노드 이름. */
    ACCOUNT_POSITIONS,
    POSITION_SYMBOL,
    POSITION_NAME,
    POSITION_QUANTITY,
    POSITION_AVERAGE_PRICE,
    POSITION_CURRENT_PRICE,

    /** 주문 배열이 들어 있는 노드 이름. */
    ORDER_LIST,
    ORDER_ID,
    ORDER_SYMBOL,
    ORDER_NAME,
    ORDER_SIDE,
    ORDER_TYPE,
    ORDER_QUANTITY,
    ORDER_FILLED_QUANTITY,
    ORDER_PRICE,
    ORDER_AVERAGE_FILLED_PRICE,
    ORDER_STATUS
}
