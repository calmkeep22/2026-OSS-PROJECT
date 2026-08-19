package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 실시간 체결을 모아 봉을 만든다.
 *
 * <p>거래소는 "실시간 차트"를 보내 주지 않는다. 봉은 체결의 요약이므로, 체결을 받아
 * 직접 쌓아야 한다. 과거 봉은 REST 로 한 번 받아 두고, 그 뒤로는 이 클래스가 마지막
 * 봉 하나만 갱신한다. 이미 끝난 봉은 다시 바뀌지 않는다.
 *
 * <p><b>거래량 처리에 함정이 있다.</b> 실시간 체결이 주는 거래량은 그 체결의 수량이
 * 아니라 <b>당일 누적</b>이다. 그대로 더하면 봉 거래량이 폭발한다. 그래서 직전 누적값과의
 * 차이를 쓴다. 종목의 첫 체결은 기준이 없어 거래량을 0으로 둔다 — 없는 값을 지어내는
 * 것보다 낫다.
 *
 * <p>여러 스레드에서 호출될 수 있어 전체를 동기화한다. 체결은 초당 수백 건까지 올 수
 * 있지만 처리 자체는 산술 몇 번이라 경합이 문제가 되지 않는다.
 */
public final class CandleAggregator {

    /**
     * 체결 하나를 반영한 결과.
     *
     * @param candle    지금 진행 중인 봉. 화면은 이것으로 마지막 봉을 다시 그린다
     * @param completed 이번 체결로 마감된 직전 봉. 없으면 비어 있다
     */
    public record Result(Candle candle, Optional<Candle> completed) {

        public Result {
            if (candle == null) {
                throw new IllegalArgumentException("봉은 필수입니다.");
            }
            completed = completed == null ? Optional.empty() : completed;
        }

        /** 이번 체결로 새 봉이 시작됐는지 여부. */
        public boolean startedNewCandle() {
            return completed.isPresent();
        }
    }

    /**
     * 종목별 진행 상태.
     *
     * @param lastCumulativeVolume 직전에 관측한 당일 누적 거래량.
     *                             {@code null} 이면 기준을 아직 모른다는 뜻이다
     */
    private record State(Candle candle, Long lastCumulativeVolume) {
    }

    private final CandleInterval interval;
    private final ZoneId zone;
    private final Map<String, State> states = new HashMap<>();

    public CandleAggregator(CandleInterval interval, ZoneId zone) {
        if (interval == null) {
            throw new IllegalArgumentException("봉 주기는 필수입니다.");
        }
        if (zone == null) {
            throw new IllegalArgumentException("시간대는 필수입니다.");
        }
        this.interval = interval;
        this.zone = zone;
    }

    /** 한국 시장 기준. */
    public CandleAggregator(CandleInterval interval) {
        this(interval, ZoneId.of("Asia/Seoul"));
    }

    public CandleInterval interval() {
        return interval;
    }

    /**
     * REST 로 받은 과거 봉의 마지막 것을 이어받는다.
     *
     * <p>이걸 하지 않으면 과거 차트의 마지막 봉과 실시간으로 새로 만든 봉이 같은 시간대에
     * 두 개 생긴다. 화면에서 마지막 봉이 두 번 그려지거나 값이 튀는 원인이 된다.
     *
     * @param lastCandle 과거 조회의 마지막 봉. 이미 지난 시간대의 봉이면 무시한다
     */
    public synchronized void prime(String symbol, Candle lastCandle, Instant now) {
        requireSymbol(symbol);
        if (lastCandle == null || now == null) {
            return;
        }
        // 지금 시각이 그 봉과 같은 구간일 때만 이어받는다. 어제 봉을 오늘 이어받으면 안 된다.
        if (!bucketStart(lastCandle.timestamp()).equals(bucketStart(now))) {
            return;
        }
        // 이 봉이 이미 담고 있는 거래량이 누적값의 어디까지인지 알 수 없으므로
        // 기준을 비워 둔다. 다음 체결부터 차이로 쌓는다.
        states.put(symbol, new State(lastCandle, null));
    }

    /** 지금 진행 중인 봉. 아직 체결을 받지 않았으면 비어 있다. */
    public synchronized Optional<Candle> current(String symbol) {
        State state = states.get(symbol);
        return state == null ? Optional.empty() : Optional.of(state.candle());
    }

    /**
     * 체결 하나를 반영한다.
     *
     * @return 갱신된 봉. 시세가 없으면 비어 있다
     */
    public synchronized Optional<Result> onQuote(Quote quote) {
        if (quote == null) {
            return Optional.empty();
        }
        String symbol = quote.symbol();
        Instant bucket = bucketStart(quote.timestamp());
        State previous = states.get(symbol);

        long tradedVolume = tradedVolume(previous, quote.cumulativeVolume());

        if (previous == null || !previous.candle().timestamp().equals(bucket)) {
            Candle started = new Candle(bucket, interval,
                    quote.price(), quote.price(), quote.price(), quote.price(), tradedVolume);
            states.put(symbol, new State(started, quote.cumulativeVolume()));
            Optional<Candle> completed = previous == null
                    ? Optional.empty()
                    : Optional.of(previous.candle());
            return Optional.of(new Result(started, completed));
        }

        Candle merged = previous.candle().merge(quote.price(), tradedVolume);
        states.put(symbol, new State(merged, quote.cumulativeVolume()));
        return Optional.of(new Result(merged, Optional.empty()));
    }

    /** 종목 하나의 상태를 비운다. 관심 종목에서 뺄 때 호출한다. */
    public synchronized void forget(String symbol) {
        states.remove(symbol);
    }

    public synchronized void reset() {
        states.clear();
    }

    // ------------------------------------------------------------------
    // 내부
    // ------------------------------------------------------------------

    /**
     * 이 체결의 거래량을 구한다.
     *
     * <p>누적값이 줄었다면 장이 새로 시작된 것으로 본다. 이때 차이를 그대로 쓰면 음수가
     * 되므로 새 누적값을 그대로 거래량으로 삼는다.
     */
    private static long tradedVolume(State previous, long cumulativeVolume) {
        if (previous == null || previous.lastCumulativeVolume() == null) {
            // 기준이 없으면 이번 체결의 거래량을 알 수 없다. 누적값을 그대로 쓰면
            // 장 시작부터의 물량이 통째로 한 봉에 들어간다.
            return 0L;
        }
        long delta = cumulativeVolume - previous.lastCumulativeVolume();
        if (delta < 0L) {
            return Math.max(0L, cumulativeVolume);
        }
        return delta;
    }

    /** 이 시각이 속한 봉 구간의 시작. */
    private Instant bucketStart(Instant timestamp) {
        ZonedDateTime moment = timestamp.atZone(zone);
        return switch (interval) {
            case MINUTE_1, MINUTE_5, MINUTE_15, MINUTE_60 -> {
                long step = interval.approximateDuration().toMinutes();
                long aligned = (moment.getMinute() / step) * step;
                yield moment.truncatedTo(ChronoUnit.HOURS).plusMinutes(aligned).toInstant();
            }
            case DAY -> moment.toLocalDate().atStartOfDay(zone).toInstant();
            case WEEK -> moment.toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant();
            case MONTH -> moment.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant();
        };
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
    }
}
