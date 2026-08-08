package org.ossproject.mocktrading;

/** 모의주문 엔진의 체결 방식. */
public enum FillMode {
    /**
     * 자동 체결 없음. {@link MockTradingEngine#fill} 을 직접 호출해야 체결된다.
     * 접수만 확인하는 화면과 테스트에 쓴다.
     */
    MANUAL,

    /** 접수 즉시 전량 체결한다. 지정가는 지정가로, 시장가는 마지막 시세로 체결한다. */
    IMMEDIATE,

    /**
     * 실시간 시세가 지정가를 지나갈 때 체결한다. 매수는 시세가 지정가 이하로,
     * 매도는 지정가 이상으로 내려오거나 올라오면 체결된다. 시장가는 접수 즉시 체결한다.
     */
    ON_QUOTE
}
