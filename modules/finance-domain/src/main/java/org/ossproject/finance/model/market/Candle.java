package org.ossproject.finance.model.market;

import org.ossproject.finance.model.PriceDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 시간축이 {@link Instant} 인 봉.
 *
 * <p>기존 {@link PricePoint} 는 날짜가 {@link LocalDate} 라 분봉과 실시간을 표현할 수 없다.
 * 일봉만 다루는 화면 계층은 {@link PricePoint} 를 계속 쓰고, 실시간·분봉을 다루는
 * 계층은 이 타입을 쓴다.
 */
public record Candle(
        Instant timestamp,
        CandleInterval interval,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
    public Candle {
        if (timestamp == null) {
            throw new IllegalArgumentException("시각은 필수입니다.");
        }
        if (interval == null) {
            throw new IllegalArgumentException("봉 주기는 필수입니다.");
        }
        requirePrice(open, "시가");
        requirePrice(high, "고가");
        requirePrice(low, "저가");
        requirePrice(close, "종가");
        if (low.compareTo(high) > 0) {
            throw new IllegalArgumentException("저가가 고가보다 높을 수 없습니다.");
        }
        if (volume < 0) {
            throw new IllegalArgumentException("거래량은 0 이상이어야 합니다.");
        }
    }

    /** 일봉 {@link PricePoint} 를 변환한다. 시각은 거래소 시간대의 그날 0시로 잡는다. */
    public static Candle fromDaily(PricePoint point, ZoneId zone) {
        if (point == null) {
            throw new IllegalArgumentException("가격 정보는 필수입니다.");
        }
        ZoneId effectiveZone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
        return new Candle(point.date().atStartOfDay(effectiveZone).toInstant(), CandleInterval.DAY,
                point.open(), point.high(), point.low(), point.close(), point.volume());
    }

    /** 화면 계층이 쓰는 {@link PricePoint} 로 되돌린다. */
    public PricePoint toPricePoint(ZoneId zone) {
        ZoneId effectiveZone = zone == null ? ZoneId.of("Asia/Seoul") : zone;
        LocalDate date = timestamp.atZone(effectiveZone).toLocalDate();
        return new PricePoint(date, open, high, low, close, volume);
    }

    /** 종가 기준 등락 금액(종가 - 시가). */
    public BigDecimal changeAmount() {
        return close.subtract(open);
    }

    /** 시가 대비 등락률(%). */
    public BigDecimal changeRate() {
        if (open.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return changeAmount()
                .divide(open, 6, RoundingMode.HALF_UP)
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

    /** 진행 중인 봉에 새 체결을 반영한다. */
    public Candle merge(BigDecimal price, long addedVolume) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
        }
        if (addedVolume < 0) {
            throw new IllegalArgumentException("거래량은 0 이상이어야 합니다.");
        }
        return new Candle(timestamp, interval, open,
                high.max(price), low.min(price), price, volume + addedVolume);
    }

    private static void requirePrice(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(label + "는 0보다 커야 합니다.");
        }
    }
}
