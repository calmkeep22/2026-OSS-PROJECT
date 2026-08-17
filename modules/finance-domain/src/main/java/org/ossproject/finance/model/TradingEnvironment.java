package org.ossproject.finance.model;

/** 주문과 계좌 데이터를 절대로 섞지 않기 위한 실행환경 구분. */
public enum TradingEnvironment {
    LOCAL_SIMULATION,
    KIWOOM_MOCK,
    KIWOOM_REAL;

    public boolean isRealMoney() {
        return this == KIWOOM_REAL;
    }
}
