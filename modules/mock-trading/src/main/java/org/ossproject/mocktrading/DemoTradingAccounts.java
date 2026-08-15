package org.ossproject.mocktrading;

import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.Position;

import java.math.BigDecimal;
import java.util.List;

/** Creates deterministic accounts used by the desktop demo. */
public final class DemoTradingAccounts {

    private DemoTradingAccounts() {
    }

    public static Account koreanStocks() {
        return new Account("00000000001", Balance.of(new BigDecimal("12500000")), List.of(
                new Position("005930", "삼성전자", 20, 0,
                        new BigDecimal("71000"), new BigDecimal("73500")),
                new Position("000660", "SK하이닉스", 5, 0,
                        new BigDecimal("183000"), new BigDecimal("190500")),
                new Position("035420", "NAVER", 3, 0,
                        new BigDecimal("204000"), new BigDecimal("198500"))));
    }
}
