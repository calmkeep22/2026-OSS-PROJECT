package org.ossproject.fake;

import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic in-memory candle source for UI development and tests.
 *
 * <p>Prices come from {@link FakeSecurityUniverse}, so the last candle closes at the same price
 * the detail screen shows. Screens therefore never need to rescale a series to make it agree
 * with the quoted price.
 */
public final class FakeCandleQueryAdapter implements CandleQueryPort {
    private final Clock clock;

    public FakeCandleQueryAdapter() {
        this(Clock.systemUTC());
    }

    public FakeCandleQueryAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<Candle> getCandles(String symbol, CandleInterval interval, int count) {
        if (interval == null) throw new IllegalArgumentException("Candle interval is required.");
        if (count < 1 || count > 1_000) throw new IllegalArgumentException("Count must be between 1 and 1000.");
        FakeSecurityUniverse.FakeSecurity security = FakeSecurityUniverse.find(symbol)
                .orElseThrow(() -> new IllegalArgumentException("Unknown security: " + symbol));

        Duration step = interval.approximateDuration();
        Instant latest = clock.instant();
        int scale = security.scale();
        List<Candle> candles = new ArrayList<>(count);
        for (int index = count - 1; index >= 0; index--) {
            BigDecimal close = security.priceAt(index);
            BigDecimal open = security.priceAt(index + 1);
            BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.004))
                    .setScale(scale, RoundingMode.HALF_UP);
            BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.996))
                    .setScale(scale, RoundingMode.HALF_UP);
            candles.add(new Candle(latest.minus(step.multipliedBy(index)), interval,
                    open, high.max(open.max(close)), low.min(open.min(close)), close,
                    security.volume() / count + index * 100L));
        }
        return List.copyOf(candles);
    }
}
