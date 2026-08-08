package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 실시간 시세 한 틱.
 *
 * <p>{@link StockDetail} 은 화면에 한 번 그려 주는 스냅샷이고, 이 타입은 WebSocket 으로
 * 초당 여러 번 흘러 들어오는 스트림 단위다.
 */
public record Quote(
        String symbol,
        BigDecimal price,
        BigDecimal previousClose,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        long bidSize,
        long askSize,
        long cumulativeVolume,
        Instant timestamp
) {
    public Quote {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("현재가는 0보다 커야 합니다.");
        }
        if (cumulativeVolume < 0) {
            throw new IllegalArgumentException("누적 거래량은 0 이상이어야 합니다.");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("시각은 필수입니다.");
        }
    }

    /** 호가 없이 체결가만 있는 간단한 틱. */
    public static Quote of(String symbol, BigDecimal price, long cumulativeVolume, Instant timestamp) {
        return new Quote(symbol, price, null, null, null, 0L, 0L, cumulativeVolume, timestamp);
    }

    /** 전일 종가 대비 등락 금액. 전일 종가를 모르면 0. */
    public BigDecimal changeAmount() {
        if (previousClose == null || previousClose.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(previousClose);
    }

    /** 전일 종가 대비 등락률(%). 전일 종가를 모르면 0. */
    public BigDecimal changeRate() {
        if (previousClose == null || previousClose.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return changeAmount()
                .divide(previousClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public PriceDirection direction() {
        int signum = changeAmount().signum();
        if (signum > 0) {
            return PriceDirection.UP;
        }
        return signum < 0 ? PriceDirection.DOWN : PriceDirection.FLAT;
    }

    /** 매수·매도 호가 차이. 호가 정보가 없으면 비어 있는 값 대신 0. */
    public BigDecimal spread() {
        if (bidPrice == null || askPrice == null) {
            return BigDecimal.ZERO;
        }
        return askPrice.subtract(bidPrice);
    }
}
