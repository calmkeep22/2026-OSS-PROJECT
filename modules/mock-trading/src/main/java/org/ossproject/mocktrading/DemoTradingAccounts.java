package org.ossproject.mocktrading;

import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.account.Balance;

import java.math.BigDecimal;
import java.util.List;

/**
 * 모의투자를 시작할 계좌를 만든다.
 *
 * <p>가상 현금만 주고 보유 종목은 두지 않는다. 증권사 모의투자도 자금만 지급하고 종목은
 * 사용자가 직접 사면서 쌓인다. 사지도 않은 종목이 보유로 잡혀 있으면, 화면을 볼 수 없는
 * 사용자는 그것이 자기가 산 것인지 앱이 넣어 둔 예시인지 구분할 수 없다.
 */
public final class DemoTradingAccounts {

    /** 모의투자 시작 자금. */
    private static final BigDecimal STARTING_CASH = new BigDecimal("12500000");

    private DemoTradingAccounts() {
    }

    /** 보유 종목 없이 시작 자금만 있는 국내주식 모의계좌. */
    public static Account koreanStocks() {
        return new Account("00000000001", Balance.of(STARTING_CASH), List.of());
    }
}
