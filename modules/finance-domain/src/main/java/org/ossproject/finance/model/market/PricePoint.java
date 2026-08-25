package org.ossproject.finance.model.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 차트가 표시하는 OHLC 한 지점.
 *
 * <p>기존 일봉 호출부는 날짜만 넘길 수 있고, 분봉은 {@link #timestamp()} 에 시각까지
 * 보존한다. 날짜만 받는 생성자를 유지해 기존 저장 자료와 화면 코드를 깨지 않는다.
 */
public record PricePoint(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        LocalDateTime timestamp
) {
    public PricePoint(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, long volume) {
        this(date, open, high, low, close, volume,
                Objects.requireNonNull(date, "date").atStartOfDay());
    }

    public PricePoint {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        timestamp = timestamp == null ? date.atStartOfDay() : timestamp;
        if (!timestamp.toLocalDate().equals(date)) {
            throw new IllegalArgumentException("날짜와 시각의 날짜 부분이 같아야 합니다.");
        }
    }
}
