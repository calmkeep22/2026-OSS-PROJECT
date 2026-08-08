package org.ossproject.anomaly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.Quote;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingAnomalyDetectorTest {

    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");
    private static final String SYMBOL = "005930";
    private static final String NAME = "삼성전자";

    private StreamingAnomalyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StreamingAnomalyDetector(new StreamingAnomalyConfig(
                Duration.ofSeconds(60),
                Duration.ofSeconds(600),
                new BigDecimal("2.0"),
                new BigDecimal("3.0"),
                Duration.ofSeconds(180),
                3));
    }

    /** 거래량이 일정하게 늘어나는 평범한 시세. */
    private List<AnomalyAlert> feed(long secondsFromStart, String price) {
        return feed(secondsFromStart, price, secondsFromStart * 100);
    }

    private List<AnomalyAlert> feed(long secondsFromStart, String price, long cumulativeVolume) {
        return detector.onQuote(NAME, Quote.of(SYMBOL, new BigDecimal(price),
                cumulativeVolume, START.plusSeconds(secondsFromStart)));
    }

    @Test
    @DisplayName("시세가 최소 개수만큼 쌓이기 전에는 판단하지 않는다")
    void waitsForMinimumSamples() {
        assertTrue(feed(0, "70000").isEmpty());
        assertTrue(feed(30, "90000").isEmpty());
    }

    @Test
    @DisplayName("구간 내 가격 상승을 급등으로 잡는다")
    void detectsPriceSurge() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");

        List<AnomalyAlert> alerts = feed(90, "72000");

        assertEquals(1, alerts.size());
        AnomalyAlert alert = alerts.get(0);
        assertEquals(AnomalyType.PRICE_SURGE, alert.type());
        assertEquals(SYMBOL, alert.symbol());
        assertTrue(alert.explanation().contains("올랐습니다"));
        assertTrue(alert.observedRatio().compareTo(new BigDecimal("2.0")) >= 0);
    }

    @Test
    @DisplayName("구간 내 가격 하락을 급락으로 잡는다")
    void detectsPriceDrop() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");

        List<AnomalyAlert> alerts = feed(90, "68000");

        assertEquals(1, alerts.size());
        assertEquals(AnomalyType.PRICE_DROP, alerts.get(0).type());
        assertTrue(alerts.get(0).explanation().contains("내렸습니다"));
    }

    @Test
    @DisplayName("임계값에 못 미치는 변동은 알리지 않는다")
    void ignoresSmallMove() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");

        assertTrue(feed(90, "70500").isEmpty());
    }

    @Test
    @DisplayName("같은 유형의 알림은 억제 시간 동안 다시 나오지 않는다")
    void suppressesRepeatedAlerts() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");
        assertEquals(1, feed(90, "72000").size());

        // 억제 시간(180초) 안이라 다시 급등해도 조용하다.
        assertTrue(feed(120, "75000").isEmpty());
        assertTrue(feed(150, "78000").isEmpty());
    }

    @Test
    @DisplayName("억제 시간이 지나면 다시 알린다")
    void alertsAgainAfterCooldown() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");
        assertEquals(1, feed(90, "72000").size());

        feed(240, "72000");
        List<AnomalyAlert> alerts = feed(280, "75000");

        assertEquals(1, alerts.size());
        assertEquals(AnomalyType.PRICE_SURGE, alerts.get(0).type());
    }

    @Test
    @DisplayName("급등과 급락은 서로 다른 유형이라 억제를 공유하지 않는다")
    void tracksCooldownPerType() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");
        assertEquals(AnomalyType.PRICE_SURGE, feed(90, "72000").get(0).type());

        // 곧바로 반대 방향으로 크게 움직이면 급락은 따로 알린다.
        // 구간 시작(60초 지점)의 70,000 대비 -2.86% 라 임계값을 넘는다.
        List<AnomalyAlert> alerts = feed(120, "68000");

        assertEquals(1, alerts.size());
        assertEquals(AnomalyType.PRICE_DROP, alerts.get(0).type());
    }

    @Test
    @DisplayName("거래량이 평소 속도의 몇 배로 늘면 급증으로 잡는다")
    void detectsVolumeSpike() {
        feed(0, "70000", 0);
        feed(300, "70000", 300_000);
        assertTrue(feed(360, "70000", 360_000).isEmpty());

        List<AnomalyAlert> alerts = feed(420, "70000", 780_000);

        assertEquals(1, alerts.size());
        assertEquals(AnomalyType.VOLUME_SPIKE, alerts.get(0).type());
        assertTrue(alerts.get(0).explanation().contains("배입니다"));
    }

    @Test
    @DisplayName("거래량이 일정하면 급증으로 보지 않는다")
    void ignoresSteadyVolume() {
        feed(0, "70000", 0);
        feed(300, "70000", 300_000);
        feed(360, "70000", 360_000);

        assertTrue(feed(420, "70000", 420_000).isEmpty());
    }

    @Test
    @DisplayName("기준선을 만들 만큼 기록이 없으면 거래량을 판단하지 않는다")
    void skipsVolumeUntilBaselineExists() {
        feed(0, "70000", 0);
        feed(10, "70000", 100);
        feed(20, "70000", 100_000);

        assertTrue(feed(30, "70000", 500_000).isEmpty());
    }

    @Test
    @DisplayName("가격과 거래량 이상이 동시에 잡히면 둘 다 알린다")
    void reportsBothTypes() {
        feed(0, "70000", 0);
        feed(300, "70000", 300_000);
        feed(360, "70000", 360_000);

        List<AnomalyAlert> alerts = feed(420, "73000", 780_000);

        assertEquals(2, alerts.size());
        assertTrue(alerts.stream().anyMatch(a -> a.type() == AnomalyType.PRICE_SURGE));
        assertTrue(alerts.stream().anyMatch(a -> a.type() == AnomalyType.VOLUME_SPIKE));
    }

    @Test
    @DisplayName("변동이 클수록 심각도가 높다")
    void escalatesSeverity() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");

        AnomalyAlert moderate = feed(90, "72000").get(0);
        detector.reset();

        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");
        AnomalyAlert severe = feed(90, "76000").get(0);

        assertEquals(AnomalySeverity.MEDIUM, moderate.severity());
        assertEquals(AnomalySeverity.HIGH, severe.severity());
    }

    @Test
    @DisplayName("종목을 잊으면 관측 기록과 억제 상태가 사라진다")
    void forgetsSymbol() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");
        assertEquals(1, feed(90, "72000").size());

        detector.forget(SYMBOL);

        assertTrue(feed(120, "75000").isEmpty());
        feed(150, "75000");
        assertEquals(1, feed(180, "78000").size());
    }

    @Test
    @DisplayName("다른 종목의 시세는 서로 영향을 주지 않는다")
    void tracksSymbolsIndependently() {
        feed(0, "70000");
        feed(30, "70000");
        feed(60, "70000");

        detector.onQuote("SK하이닉스",
                Quote.of("000660", new BigDecimal("190000"), 100, START.plusSeconds(60)));
        detector.onQuote("SK하이닉스",
                Quote.of("000660", new BigDecimal("190000"), 200, START.plusSeconds(70)));

        List<AnomalyAlert> alerts = feed(90, "72000");

        assertEquals(1, alerts.size());
        assertEquals(SYMBOL, alerts.get(0).symbol());
    }

    @Test
    @DisplayName("null 시세는 무시한다")
    void ignoresNullQuote() {
        assertTrue(detector.onQuote(NAME, null).isEmpty());
    }
}
