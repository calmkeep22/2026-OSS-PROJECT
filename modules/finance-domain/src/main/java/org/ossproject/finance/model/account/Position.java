package org.ossproject.finance.model.account;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 보유 종목. 매도 주문으로 묶인 수량을 따로 관리한다.
 *
 * <p>This is the single holding model shared by account views and order processing.
 */
public record Position(
        String symbol,
        String name,
        long quantity,
        long lockedQuantity,
        BigDecimal averagePrice,
        BigDecimal currentPrice,
        ReportedValuation reported
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
        reported = reported == null ? ReportedValuation.none() : reported;
    }

    /** 증권사 평가 값 없이 만드는 보유 종목. 모의 거래가 쓴다. */
    public Position(String symbol, String name, long quantity, long lockedQuantity,
                    BigDecimal averagePrice, BigDecimal currentPrice) {
        this(symbol, name, quantity, lockedQuantity, averagePrice, currentPrice, null);
    }

    public static Position of(String symbol, String name, long quantity, BigDecimal averagePrice) {
        return new Position(symbol, name, quantity, 0L, averagePrice, averagePrice);
    }

    /** 지금 매도 주문에 낼 수 있는 수량. */
    public long availableQuantity() {
        return quantity - lockedQuantity;
    }

    /** 평가금액. 증권사가 준 값이 있으면 그것을 쓴다. */
    public BigDecimal marketValue() {
        return reported.or(reported.evaluation(),
                currentPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /** 매입금액. 증권사가 준 값이 있으면 그것을 쓴다. */
    public BigDecimal costBasis() {
        return reported.or(reported.purchaseAmount(),
                averagePrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /**
     * 평가손익. 증권사가 준 값이 있으면 그것을 쓴다.
     *
     * <p>직접 뺀 값은 매매수수료와 거래세를 세지 않아 증권사 화면보다 이익 쪽으로 치우친다.
     */
    public BigDecimal profitLoss() {
        return reported.or(reported.profitLoss(), marketValue().subtract(costBasis()));
    }

    /** 평가 값이 증권사가 계산한 것인지. 거짓이면 앱이 직접 계산한 값이다. */
    public boolean valuationReportedByBroker() {
        return reported.isPresent();
    }

    /** 수익률(%). 매입 원가가 0이면 0을 돌려준다. */
    public BigDecimal profitLossRate() {
        if (reported.profitLossRate() != null) {
            return reported.profitLossRate();
        }
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
        return new Position(symbol, name, quantity, lockedQuantity + amount, averagePrice, currentPrice, reported);
    }

    /** 취소·거부·체결 확정 시 묶인 수량을 푼다. */
    public Position unlock(long amount) {
        requirePositive(amount);
        if (amount > lockedQuantity) {
            throw new IllegalStateException(
                    "묶인 수량보다 많이 풀 수 없습니다. 대기 " + lockedQuantity + ", 요청 " + amount);
        }
        return new Position(symbol, name, quantity, lockedQuantity - amount, averagePrice, currentPrice, reported);
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
        // 수량이 바뀌면 증권사가 준 평가 값은 낡은 값이다.
        return new Position(symbol, name, newQuantity, lockedQuantity, newAverage, currentPrice, null);
    }

    /** 매도 체결. 평균 단가는 유지한다. */
    public Position removeShares(long amount) {
        requirePositive(amount);
        if (amount > quantity) {
            throw new IllegalStateException(
                    "보유 수량보다 많이 팔 수 없습니다. 보유 " + quantity + ", 매도 " + amount);
        }
        long newLocked = Math.min(lockedQuantity, quantity - amount);
        return new Position(symbol, name, quantity - amount, newLocked, averagePrice, currentPrice, null);
    }

    /** 실시간 시세 반영. */
    public Position withCurrentPrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("현재가는 0 이상이어야 합니다.");
        }
        // 시세가 움직였으므로 증권사가 준 평가 값은 더 이상 맞지 않는다.
        return new Position(symbol, name, quantity, lockedQuantity, averagePrice, price, null);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("수량은 1 이상이어야 합니다.");
        }
    }
}
