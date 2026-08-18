package org.ossproject.kiwoom.stream;

/**
 * 키움 실시간 시세 종류.
 *
 * <p>{@code REG} 패킷의 {@code type} 배열에 넣는 코드다. 한 번의 등록으로 여러 종류를
 * 함께 구독할 수 있다.
 */
public enum KiwoomRealtimeType {

    /** 주식체결. 체결가·거래량이 갱신될 때마다 온다. */
    TRADE("0B", "주식체결"),

    /** 주식호가잔량. 10단계 호가와 잔량. */
    ORDER_BOOK("0D", "주식호가잔량"),

    /** 주식우선호가. 최우선 매도·매수 호가만 온다. */
    BEST_QUOTE("0C", "주식우선호가"),

    /** 주문체결. 내 주문의 접수·체결 통보. */
    ORDER_FILL("00", "주문체결");

    private final String code;
    private final String displayName;

    KiwoomRealtimeType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** 서버가 보낸 코드로 종류를 찾는다. 모르는 코드면 {@code null}. */
    public static KiwoomRealtimeType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (KiwoomRealtimeType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
