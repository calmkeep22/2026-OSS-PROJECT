package org.ossproject.fake;

import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Deterministic in-memory candle source for UI development and tests. */
public final class FakeCandleQueryAdapter implements CandleQueryPort {
    private final Clock clock;

    public FakeCandleQueryAdapter() {
        this(Clock.systemUTC());
    }

    public FakeCandleQueryAdapter(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<Candle> getCandles(String symbol, CandleInterval interval, int count) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Symbol is required.");
        if (interval == null) throw new IllegalArgumentException("Candle interval is required.");
        if (count < 1 || count > 1_000) throw new IllegalArgumentException("Count must be between 1 and 1000.");

        Duration step = interval.approximateDuration();
        Instant start = clock.instant().minus(step.multipliedBy(count));
        List<Candle> candles = new ArrayList<>(count);
        BigDecimal base = new BigDecimal("70000");
        for (int index = 0; index < count; index++) {
            BigDecimal open = base.add(BigDecimal.valueOf(index * 100L));
            BigDecimal close = open.add(BigDecimal.valueOf(index % 2 == 0 ? 300 : -200));
            candles.add(new Candle(start.plus(step.multipliedBy(index + 1L)), interval,
                    open, open.max(close).add(BigDecimal.valueOf(100)),
                    open.min(close).subtract(BigDecimal.valueOf(100)), close,
                    10_000L + index * 1_000L));
        }
        return List.copyOf(candles);
    }

}
