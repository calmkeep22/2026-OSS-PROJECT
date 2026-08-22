package org.ossproject.finance.model.account;

import java.math.BigDecimal;

/**
 * 예수금과 주문으로 묶인 금액.
 *
 * <p>매수 주문을 접수하면 예상 금액만큼 {@code locked} 에 잡아 두고, 체결되면 실제 체결
 * 금액만큼 {@code cash} 에서 빠진다. 취소·거부되면 잡아 둔 금액을 되돌린다.
 * 그래서 "현금은 있는데 주문이 안 되는" 상황이 정확히 표현된다.
 */
public record Balance(BigDecimal cash, BigDecimal locked) {

    public static final Balance ZERO = new Balance(BigDecimal.ZERO, BigDecimal.ZERO);

    public Balance {
        if (cash == null || cash.signum() < 0) {
            throw new IllegalArgumentException("예수금은 0 이상이어야 합니다.");
        }
        if (locked == null || locked.signum() < 0) {
            throw new IllegalArgumentException("주문 대기 금액은 0 이상이어야 합니다.");
        }
        if (locked.compareTo(cash) > 0) {
            throw new IllegalArgumentException("주문 대기 금액이 예수금을 초과할 수 없습니다.");
        }
    }

    public static Balance of(BigDecimal cash) {
        return new Balance(cash, BigDecimal.ZERO);
    }

    /** 지금 새 주문에 쓸 수 있는 금액. */
    public BigDecimal available() {
        return cash.subtract(locked);
    }

    /** 매수 주문 접수 시 금액을 잡아 둔다. */
    public Balance lock(BigDecimal amount) {
        requirePositive(amount);
        if (amount.compareTo(available()) > 0) {
            throw new IllegalStateException(
                    "주문 가능 금액이 부족합니다. 가능 " + available() + ", 필요 " + amount);
        }
        return new Balance(cash, locked.add(amount));
    }

    /** 취소·거부·체결 확정 시 잡아 둔 금액을 푼다. */
    public Balance unlock(BigDecimal amount) {
        requirePositive(amount);
        if (amount.compareTo(locked) > 0) {
            throw new IllegalStateException(
                    "잡아 둔 금액보다 많이 풀 수 없습니다. 대기 " + locked + ", 요청 " + amount);
        }
        return new Balance(cash, locked.subtract(amount));
    }

    /** 매수 체결로 현금이 실제로 빠져나간다. */
    public Balance withdraw(BigDecimal amount) {
        requirePositive(amount);
        BigDecimal newCash = cash.subtract(amount);
        if (newCash.signum() < 0) {
            throw new IllegalStateException("예수금이 부족합니다. 잔액 " + cash + ", 출금 " + amount);
        }
        if (locked.compareTo(newCash) > 0) {
            throw new IllegalStateException("출금하면 주문 대기 금액을 감당할 수 없습니다.");
        }
        return new Balance(newCash, locked);
    }

    /** 매도 체결 대금이 들어온다. */
    public Balance deposit(BigDecimal amount) {
        requirePositive(amount);
        return new Balance(cash.add(amount), locked);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }
    }
}
