package org.ossproject.ai.http;

import org.ossproject.finance.model.Candle;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 분석에 넣어도 되는 봉만 고른다.
 *
 * <p>장중에 받은 오늘 일봉은 종가가 아니라 현재가다. 그대로 넣으면 "내일을 맞힌다" 가
 * "이미 본 값을 되읽는다" 가 되어, 맞는 것처럼 보이지만 아무 뜻이 없는 결과가 나온다.
 *
 * <p>한국 장은 15시 30분에 닫는다. 체결과 정정에 시간이 걸리므로 마감 20분 뒤부터 오늘
 * 봉을 확정으로 본다. 그전이면 마지막 봉을 뺀다.
 *
 * <p>실시간 시세로 마지막 봉을 계속 갱신하고 있어서 이 판단이 특히 중요하다. 갱신되는
 * 값을 확정 종가로 넘기면 매 갱신마다 다른 예측이 나온다.
 */
public final class TradingSessionBars {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 마감 15:30 에 정정 여유 20분을 더한 시각. */
    private static final LocalTime SETTLED_AFTER = LocalTime.of(15, 50);

    private final Clock clock;

    public TradingSessionBars(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 확정된 봉만 남긴다.
     *
     * <p>마지막 봉이 오늘 것이고 아직 확정 시각 전이면 뺀다. 그 밖에는 그대로 둔다.
     */
    public List<Candle> settled(List<Candle> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        Candle last = bars.get(bars.size() - 1);
        return isUnsettled(last) ? List.copyOf(bars.subList(0, bars.size() - 1)) : List.copyOf(bars);
    }

    /** 이 봉이 아직 확정되지 않았는지. */
    public boolean isUnsettled(Candle candle) {
        if (candle == null) {
            return false;
        }
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), SEOUL);
        LocalDate candleDate = candle.timestamp().atZone(SEOUL).toLocalDate();
        if (!candleDate.equals(now.toLocalDate())) {
            return false;
        }
        return now.toLocalTime().isBefore(SETTLED_AFTER);
    }
}
