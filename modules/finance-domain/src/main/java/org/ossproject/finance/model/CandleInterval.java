package org.ossproject.finance.model;

import java.time.Duration;

/**
 * 봉 주기.
 *
 * <p>기존 {@link PricePeriod} 는 "차트에 며칠치를 보여줄까"라는 조회 범위이고,
 * 이 타입은 "봉 하나가 몇 분인가"라는 해상도다. 둘은 다른 축이라 함께 쓴다.
 */
public enum CandleInterval {
    MINUTE_1("1분봉", Duration.ofMinutes(1)),
    MINUTE_5("5분봉", Duration.ofMinutes(5)),
    MINUTE_15("15분봉", Duration.ofMinutes(15)),
    MINUTE_60("60분봉", Duration.ofHours(1)),
    DAY("일봉", Duration.ofDays(1)),
    WEEK("주봉", Duration.ofDays(7)),
    MONTH("월봉", Duration.ofDays(30));

    private final String displayName;
    private final Duration duration;

    CandleInterval(String displayName, Duration duration) {
        this.displayName = displayName;
        this.duration = duration;
    }

    public String displayName() {
        return displayName;
    }

    /** 주봉·월봉은 달력에 따라 길이가 달라지므로 근사값이다. */
    public Duration approximateDuration() {
        return duration;
    }

    public boolean isIntraday() {
        return this == MINUTE_1 || this == MINUTE_5 || this == MINUTE_15 || this == MINUTE_60;
    }
}
