package org.ossproject.anomaly;

import org.ossproject.finance.model.market.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 실시간 시세 스트림에서 이상 움직임을 찾는다.
 *
 * <p>{@link RuleBasedAnomalyDetector} 는 일봉 목록을 통째로 받아 한 번에 판단하는 배치용이다.
 * 이 클래스는 WebSocket 으로 초당 여러 번 흘러 들어오는 시세를 받아 이동 구간으로 판단한다.
 *
 * <p>같은 종목의 같은 유형 알림은 억제 시간 동안 다시 내보내지 않는다. 알림이 음성으로
 * 읽히기 때문에, 억제가 없으면 급등 중인 종목 하나가 다른 모든 안내를 덮어 버린다.
 */
public final class StreamingAnomalyDetector {

    /** 한 시점의 시세. 거래량은 당일 누적값이다. */
    private record Sample(Instant timestamp, BigDecimal price, long cumulativeVolume) {
    }

    private final StreamingAnomalyConfig config;
    private final Map<String, Deque<Sample>> samples = new HashMap<>();
    private final Map<String, Instant> lastAlertAt = new HashMap<>();

    public StreamingAnomalyDetector(StreamingAnomalyConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("설정은 필수입니다.");
        }
        this.config = config;
    }

    public StreamingAnomalyDetector() {
        this(StreamingAnomalyConfig.defaults());
    }

    /**
     * 시세 한 건을 받아 이상 알림을 돌려준다.
     *
     * @param stockName 음성 안내에 쓸 종목명
     * @return 이번 시세로 새로 발생한 알림. 없으면 빈 목록
     */
    public synchronized List<AnomalyAlert> onQuote(String stockName, Quote quote) {
        if (quote == null) {
            return List.of();
        }
        String symbol = quote.symbol();
        Deque<Sample> history = samples.computeIfAbsent(symbol, key -> new ArrayDeque<>());
        history.addLast(new Sample(quote.timestamp(), quote.price(), quote.cumulativeVolume()));
        evictOutdated(history, quote.timestamp());

        if (history.size() < config.minimumSamples()) {
            return List.of();
        }

        List<AnomalyAlert> alerts = new ArrayList<>(2);
        detectPriceMove(symbol, stockName, history, quote).ifPresent(alerts::add);
        detectVolumeSpike(symbol, stockName, history, quote).ifPresent(alerts::add);
        return List.copyOf(alerts);
    }

    /** 종목 하나의 관측 기록을 비운다. 관심 종목에서 뺄 때 호출한다. */
    public synchronized void forget(String symbol) {
        samples.remove(symbol);
        lastAlertAt.keySet().removeIf(key -> key.startsWith(symbol + "|"));
    }

    public synchronized void reset() {
        samples.clear();
        lastAlertAt.clear();
    }

    // ------------------------------------------------------------------
    // 탐지
    // ------------------------------------------------------------------

    private java.util.Optional<AnomalyAlert> detectPriceMove(
            String symbol, String stockName, Deque<Sample> history, Quote quote) {

        Sample reference = oldestWithin(history, quote.timestamp(), config.window());
        if (reference == null || reference.price().signum() <= 0) {
            return java.util.Optional.empty();
        }

        BigDecimal changePercent = quote.price().subtract(reference.price())
                .divide(reference.price(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (changePercent.abs().compareTo(config.priceThresholdPercent()) < 0) {
            return java.util.Optional.empty();
        }

        AnomalyType type = changePercent.signum() >= 0 ? AnomalyType.PRICE_SURGE : AnomalyType.PRICE_DROP;
        if (isSuppressed(symbol, type, quote.timestamp())) {
            return java.util.Optional.empty();
        }

        BigDecimal magnitude = changePercent.abs().setScale(2, RoundingMode.HALF_UP);
        AnomalySeverity severity = magnitude.compareTo(config.priceThresholdPercent()
                .multiply(BigDecimal.valueOf(2))) >= 0 ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM;

        String explanation = stockName + " 최근 " + describeWindow(config.window()) + " 동안 가격이 "
                + magnitude + "% " + (type == AnomalyType.PRICE_SURGE ? "올랐습니다." : "내렸습니다.");

        record(symbol, type, quote.timestamp());
        return java.util.Optional.of(new AnomalyAlert(symbol, stockName, type, severity,
                magnitude, explanation, quote.timestamp()));
    }

    /**
     * 거래량 급증 판단.
     *
     * <p>누적 거래량만 오므로 구간 증가분을 쓴다. 관측 구간의 증가분을, 더 긴 기준선 구간의
     * 평균 증가분(같은 길이로 환산)과 비교한다. 절대량이 아니라 속도를 보기 때문에
     * 거래가 원래 많은 종목과 적은 종목을 같은 기준으로 다룰 수 있다.
     */
    private java.util.Optional<AnomalyAlert> detectVolumeSpike(
            String symbol, String stockName, Deque<Sample> history, Quote quote) {

        Sample windowStart = oldestWithin(history, quote.timestamp(), config.window());
        Sample baselineStart = history.peekFirst();
        if (windowStart == null || baselineStart == null || windowStart == baselineStart) {
            return java.util.Optional.empty();
        }

        long recentVolume = quote.cumulativeVolume() - windowStart.cumulativeVolume();
        long baselineVolume = quote.cumulativeVolume() - baselineStart.cumulativeVolume();
        if (recentVolume <= 0 || baselineVolume <= 0) {
            return java.util.Optional.empty();
        }

        Duration baselineSpan = Duration.between(baselineStart.timestamp(), quote.timestamp());
        if (baselineSpan.compareTo(config.window()) <= 0) {
            // 기준선을 만들 만큼 기록이 쌓이지 않았다.
            return java.util.Optional.empty();
        }

        BigDecimal expected = BigDecimal.valueOf(baselineVolume)
                .multiply(BigDecimal.valueOf(config.window().toMillis()))
                .divide(BigDecimal.valueOf(baselineSpan.toMillis()), 6, RoundingMode.HALF_UP);
        if (expected.signum() <= 0) {
            return java.util.Optional.empty();
        }

        BigDecimal ratio = BigDecimal.valueOf(recentVolume)
                .divide(expected, 2, RoundingMode.HALF_UP);
        if (ratio.compareTo(config.volumeThresholdRatio()) < 0) {
            return java.util.Optional.empty();
        }
        if (isSuppressed(symbol, AnomalyType.VOLUME_SPIKE, quote.timestamp())) {
            return java.util.Optional.empty();
        }

        AnomalySeverity severity = ratio.compareTo(config.volumeThresholdRatio()
                .multiply(BigDecimal.valueOf(2))) >= 0 ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM;
        String explanation = stockName + " 최근 " + describeWindow(config.window())
                + " 거래량이 평소의 " + ratio + "배입니다.";

        record(symbol, AnomalyType.VOLUME_SPIKE, quote.timestamp());
        return java.util.Optional.of(new AnomalyAlert(symbol, stockName, AnomalyType.VOLUME_SPIKE,
                severity, ratio, explanation, quote.timestamp()));
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private void evictOutdated(Deque<Sample> history, Instant now) {
        Instant cutoff = now.minus(config.baselineWindow());
        while (history.size() > 1 && history.peekFirst().timestamp().isBefore(cutoff)) {
            history.removeFirst();
        }
    }

    /** 구간 시작 시점에 가장 가까운(그보다 오래되지 않은) 시세. */
    private Sample oldestWithin(Deque<Sample> history, Instant now, Duration span) {
        Instant from = now.minus(span);
        Sample candidate = null;
        for (Sample sample : history) {
            if (!sample.timestamp().isBefore(from)) {
                candidate = sample;
                break;
            }
            candidate = sample;
        }
        return candidate;
    }

    private boolean isSuppressed(String symbol, AnomalyType type, Instant now) {
        if (config.cooldown().isZero()) {
            return false;
        }
        Instant last = lastAlertAt.get(key(symbol, type));
        return last != null && Duration.between(last, now).compareTo(config.cooldown()) < 0;
    }

    private void record(String symbol, AnomalyType type, Instant now) {
        lastAlertAt.put(key(symbol, type), now);
    }

    private static String key(String symbol, AnomalyType type) {
        return symbol + "|" + type.name();
    }

    private static String describeWindow(Duration window) {
        long seconds = window.toSeconds();
        if (seconds % 60 == 0) {
            return (seconds / 60) + "분";
        }
        return seconds + "초";
    }
}
