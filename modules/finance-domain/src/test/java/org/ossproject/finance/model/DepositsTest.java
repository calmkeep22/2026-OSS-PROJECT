package org.ossproject.finance.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DepositsTest {

    @Test void treatsANegativeSettledCashAsAShortfall() {
        Deposits deposits = new Deposits(new BigDecimal("1000000"), new BigDecimal("-500000"),
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertTrue(deposits.hasShortfall());
        assertEquals(0, new BigDecimal("500000").compareTo(deposits.shortfall()));
    }

    @Test void reportsNoShortfallWhenSettlementLeavesCashBehind() {
        Deposits deposits = new Deposits(new BigDecimal("1000000"), new BigDecimal("780000"),
                new BigDecimal("780000"), new BigDecimal("780000"));

        assertFalse(deposits.hasShortfall());
        assertEquals(0, BigDecimal.ZERO.compareTo(deposits.shortfall()));
    }

    /** 모의 원장에는 결제 지연이 없다. 네 값이 같고 주문 대기분만 빠진다. */
    @Test void derivesEveryStageFromASimulationLedger() {
        Balance ledger = Balance.of(new BigDecimal("1000000")).lock(new BigDecimal("300000"));
        Deposits deposits = Deposits.from(ledger);

        assertEquals(0, new BigDecimal("1000000").compareTo(deposits.cash()));
        assertEquals(0, new BigDecimal("1000000").compareTo(deposits.settledCash()));
        assertEquals(0, new BigDecimal("700000").compareTo(deposits.orderable()));
    }

    @Test void rejectsANegativeOrderableAmount() {
        assertThrows(IllegalArgumentException.class, () -> new Deposits(
                BigDecimal.TEN, BigDecimal.TEN, new BigDecimal("-1"), BigDecimal.TEN));
    }

    /**
     * 매수 당일에는 예수금이 아직 안 빠진다. 총자산을 예수금으로 계산하면 매수 금액이
     * 현금과 주식 양쪽에서 세어져 부풀어 오른다.
     */
    @Test void totalAssetsUseSettledCashSoABuyDoesNotInflateThem() {
        Deposits afterBuy = new Deposits(new BigDecimal("10000000"), new BigDecimal("9780000"),
                new BigDecimal("9780000"), new BigDecimal("9780000"));
        Account account = new Account("00000000001", Balance.of(new BigDecimal("10000000")),
                List.of(Position.of("005930", "삼성전자", 20, new BigDecimal("11000"))),
                afterBuy, null);

        assertEquals(0, new BigDecimal("10000000").compareTo(account.totalAssets()));
    }

    @Test void aBrokerReportedTotalWinsOverOurOwnSum() {
        Account account = new Account("00000000001", Balance.of(new BigDecimal("10000000")),
                List.of(), null, new BigDecimal("10500000"));

        assertTrue(account.totalAssetsReportedByBroker());
        assertEquals(0, new BigDecimal("10500000").compareTo(account.totalAssets()));
    }

    /** 시세로 보유 평가액이 바뀌면 증권사가 준 총액은 낡은 값이다. */
    @Test void dropsTheBrokerTotalOncePositionsMoveOnLivePrices() {
        Account account = new Account("00000000001", Balance.of(new BigDecimal("1000000")),
                List.of(Position.of("005930", "삼성전자", 10, new BigDecimal("70000"))),
                null, new BigDecimal("1700000"));

        Account moved = account.applyQuote(new Quote("005930", new BigDecimal("71000"),
                null, null, null, 0L, 0L, 0L, java.time.Instant.EPOCH));

        assertFalse(moved.totalAssetsReportedByBroker());
        assertEquals(0, new BigDecimal("1710000").compareTo(moved.totalAssets()));
    }
}
