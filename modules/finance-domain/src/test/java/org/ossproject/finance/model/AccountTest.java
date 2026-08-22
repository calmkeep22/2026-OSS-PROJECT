package org.ossproject.finance.model;

import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.account.Balance;
import org.ossproject.finance.model.account.Position;
import org.ossproject.finance.model.market.Quote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    @Test
    @DisplayName("주문 대기 금액만큼 주문 가능 금액이 줄어든다")
    void locksCash() {
        Balance balance = Balance.of(new BigDecimal("1000000"));

        Balance locked = balance.lock(new BigDecimal("700000"));

        assertEquals(0, new BigDecimal("300000").compareTo(locked.available()));
        assertEquals(0, new BigDecimal("1000000").compareTo(locked.cash()));
    }

    @Test
    @DisplayName("주문 가능 금액을 넘는 금액은 잡을 수 없다")
    void rejectsOverLock() {
        Balance balance = Balance.of(new BigDecimal("1000000")).lock(new BigDecimal("900000"));

        assertThrows(IllegalStateException.class, () -> balance.lock(new BigDecimal("200000")));
    }

    @Test
    @DisplayName("매수 체결은 잡아 둔 금액을 풀고 실제 체결 금액만 출금한다")
    void settlesBuy() {
        Balance afterLock = Balance.of(new BigDecimal("1000000")).lock(new BigDecimal("700000"));

        // 지정가 70,000 * 10주로 잡았지만 69,000에 체결된 경우
        Balance settled = afterLock.unlock(new BigDecimal("700000")).withdraw(new BigDecimal("690000"));

        assertEquals(0, new BigDecimal("310000").compareTo(settled.cash()));
        assertEquals(0, BigDecimal.ZERO.compareTo(settled.locked()));
    }

    @Test
    @DisplayName("매도 대기 수량만큼 매도 가능 수량이 줄어든다")
    void locksShares() {
        Position position = Position.of("005930", "삼성전자", 10, new BigDecimal("70000"));

        Position locked = position.lock(6);

        assertEquals(4L, locked.availableQuantity());
        assertEquals(10L, locked.quantity());
        assertThrows(IllegalStateException.class, () -> locked.lock(5));
    }

    @Test
    @DisplayName("매수 체결 시 평균 단가를 가중 평균으로 다시 계산한다")
    void recalculatesAveragePrice() {
        Position position = Position.of("005930", "삼성전자", 10, new BigDecimal("70000"));

        Position added = position.addShares(10, new BigDecimal("80000"));

        assertEquals(20L, added.quantity());
        assertEquals(0, new BigDecimal("75000.00").compareTo(added.averagePrice()));
    }

    @Test
    @DisplayName("수익률을 백분율로 계산한다")
    void calculatesProfitLossRate() {
        Position position = new Position("005930", "삼성전자", 10, 0,
                new BigDecimal("70000"), new BigDecimal("73500"));

        assertEquals(0, new BigDecimal("35000").compareTo(position.profitLoss()));
        assertEquals(0, new BigDecimal("5.00").compareTo(position.profitLossRate()));
    }

    @Test
    @DisplayName("수량이 0이 되면 보유 목록에서 빠진다")
    void dropsEmptyPosition() {
        Account account = Account.of("12345678901", new BigDecimal("1000000"))
                .withPosition(Position.of("005930", "삼성전자", 10, new BigDecimal("70000")));

        Account sold = account.withPosition(account.position("005930").orElseThrow().removeShares(10));

        assertTrue(sold.positions().isEmpty());
        assertTrue(sold.position("005930").isEmpty());
    }

    @Test
    @DisplayName("실시간 시세를 보유 종목 현재가에 반영한다")
    void appliesQuote() {
        Account account = Account.of("12345678901", new BigDecimal("1000000"))
                .withPosition(Position.of("005930", "삼성전자", 10, new BigDecimal("70000")));

        Account updated = account.applyQuote(Quote.of("005930", new BigDecimal("75000"),
                1_000L, Instant.parse("2026-08-08T01:00:00Z")));

        assertEquals(0, new BigDecimal("750000").compareTo(updated.totalMarketValue()));
        assertEquals(0, new BigDecimal("1750000").compareTo(updated.totalAssets()));
        assertEquals(0, new BigDecimal("50000").compareTo(updated.totalProfitLoss()));
    }

    @Test
    @DisplayName("보유하지 않은 종목의 시세는 무시한다")
    void ignoresUnrelatedQuote() {
        Account account = Account.of("12345678901", new BigDecimal("1000000"))
                .withPosition(Position.of("005930", "삼성전자", 10, new BigDecimal("70000")));

        Account updated = account.applyQuote(Quote.of("000660", new BigDecimal("190000"),
                1_000L, Instant.parse("2026-08-08T01:00:00Z")));

        assertEquals(account, updated);
    }

    @Test
    @DisplayName("계좌이 화면에 필요한 보유종목과 잔고를 직접 제공한다")
    void exposesPositionsAndBalance() {
        Account account = Account.of("12345678901", new BigDecimal("1000000"))
                .withPosition(Position.of("005930", "삼성전자", 10, new BigDecimal("70000")));

        assertEquals(1, account.positions().size());
        assertEquals("삼성전자", account.positions().get(0).name());
        assertEquals(0, new BigDecimal("1000000").compareTo(account.balance().cash()));
    }

    @Test
    @DisplayName("계좌번호는 뒤 4자리만 남기고 가린다")
    void masksAccountNo() {
        assertEquals("*******8901", Account.maskAccountNo("12345678901"));
        assertEquals("****", Account.maskAccountNo("123"));
        assertEquals("****", Account.maskAccountNo(null));
    }
}
