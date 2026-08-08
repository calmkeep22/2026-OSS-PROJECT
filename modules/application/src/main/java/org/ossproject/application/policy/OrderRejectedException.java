package org.ossproject.application.policy;

/**
 * 안전장치가 주문을 막았을 때 던진다.
 *
 * <p>{@link #getMessage()} 는 그대로 음성으로 읽어 줄 수 있는 한국어 문장이다.
 */
public class OrderRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 거부 사유. 화면 계층이 사유별로 다른 상태음을 낼 수 있도록 분류한다. */
    public enum Reason {
        ORDER_AMOUNT_EXCEEDED("단일 주문 한도 초과"),
        DAILY_AMOUNT_EXCEEDED("일일 주문 한도 초과"),
        RATE_LIMIT_EXCEEDED("주문 속도 제한"),
        DUPLICATE_ORDER("중복 주문");

        private final String displayName;

        Reason(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private final transient Reason reason;

    public OrderRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
