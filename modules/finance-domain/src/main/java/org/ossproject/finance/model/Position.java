package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 보유 종목. 매도 주문으로 묶인 수량을 따로 관리한다.
 *
 * <p>기존 {@link Holding} 은 화면 표시용 값 객체라 그대로 두고, 주문 처리 계층은 이 타입을
 * 사용한다. {@link #toHolding()} 으로 변환한다.
 */
public record Position(
        String symbol,
        String name,
        long quantity,
        long lockedQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice
) {
    public Position {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("종목명은 필수입니다.");
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("보유 수량은 0 이상이어야 합니다.");
        }
        if (lockedQuantity < 0 || lockedQuantity > quantity) {
            throw new IllegalArgumentException("매도 대기 수량이 보유 수량 범위를 벗어났습니다.");
        }
        if (averagePrice == null || averagePrice.signum() < 0) {
            throw new IllegalArgumentException("평균 단가는 0 이상이어야 합니다.");
        }
        if (currentPrice == null || currentPrice.signum() < 0) {
            throw new IllegalArgumentException("현재가는 0 이상이어야 합니다.");
        }
    }

    public static Position of(String symbol, String name, long quantity, BigDecimal averagePrice) {
        return new Position(symbol, name, quantity, 0L, averagePrice, averagePrice);
    }

    /** 지금 매도 주문에 낼 수 있는 수량. */
    public long availableQuantity() {
        return quantity - lockedQuantity;
    }

    public BigDecimal marketValue() {
        return currentPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal costBasis() {
        return averagePrice.multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal profitLoss() {
        return marketValue().subtract(costBasis());
    }

    /** 수익률(%). 매입 원가가 0이면 0을 돌려준다. */
    public BigDecimal profitLossRate() {
        BigDecimal cost = costBasis();
        if (cost.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return profitLoss()
                .divide(cost, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 매도 주문 접수 시 수량을 묶는다. */
    public Position lock(long amount) {
        requirePositive(amount);
        if (amount > availableQuantity()) {
            throw new IllegalStateException(
                    "매도 가능 수량이 부족합니다. 가능 " + availableQuantity() + ", 필요 " + amount);
        }
        return new Position(symbol, name, quantity, lockedQuantity + amount, averagePrice, currentPrice);
    }

    /** 취소·거부·체결 확정 시 묶인 수량을 푼다. */
    public Position unlock(long amount) {
        requirePositive(amount);
        if (amount > lockedQuantity) {
            throw new IllegalStateException(
                    "묶인 수량보다 많이 풀 수 없습니다. 대기 " + lockedQuantity + ", 요청 " + amount);
        }
        return new Position(symbol, name, quantity, lockedQuantity - amount, averagePrice, currentPrice);
    }

    /** 매수 체결. 평균 단가를 가중 평균으로 다시 계산한다. */
    public Position addShares(long amount, BigDecimal price) {
        requirePositive(amount);
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("체결 가격은 0보다 커야 합니다.");
        }
        long newQuantity = quantity + amount;
        BigDecimal newCost = costBasis().add(price.multiply(BigDecimal.valueOf(amount)));
        BigDecimal newAverage = newCost.divide(BigDecimal.valueOf(newQuantity), 2, RoundingMode.HALF_UP);
        return new Position(symbol, name, newQuantity, lockedQuantity, newAverage, currentPrice);
    }

    /** 매도 체결. 평균 단가는 유지한다. */
    public Position removeShares(long amount) {
        requirePositive(amount);
        if (amount > quantity) {
            throw new IllegalStateException(
                    "보유 수량보다 많이 팔 수 없습니다. 보유 " + quantity + ", 매도 " + amount);
        }
        long newLocked = Math.min(lockedQuantity, quantity - amount);
        return new Position(symbol, name, quantity - amount, newLocked, averagePrice, currentPrice);
    }

    /** 실시간 시세 반영. */
    public Position withCurrentPrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("현재가는 0 이상이어야 합니다.");
        }
        return new Position(symbol, name, quantity, lockedQuantity, averagePrice, price);
    }

    /** 화면 표시용 {@link Holding} 으로 변환한다. */
    public Holding toHolding() {
        return new Holding(symbol, name, quantity, averagePrice, currentPrice);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
    }
}
