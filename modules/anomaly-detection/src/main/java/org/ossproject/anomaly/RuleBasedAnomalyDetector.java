package org.ossproject.anomaly;

import org.ossproject.finance.model.market.PricePoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RuleBasedAnomalyDetector {
    private final BigDecimal priceThresholdPercent;
    private final BigDecimal volumeThresholdRatio;

    public RuleBasedAnomalyDetector() { this(new BigDecimal("4.0"), new BigDecimal("1.5")); }
    public RuleBasedAnomalyDetector(BigDecimal priceThresholdPercent, BigDecimal volumeThresholdRatio) {
        this.priceThresholdPercent = priceThresholdPercent;
        this.volumeThresholdRatio = volumeThresholdRatio;
    }

    public List<AnomalyAlert> detect(String symbol, String stockName, List<PricePoint> history) {
        if (history.size() < 2) return List.of();
        PricePoint latest = history.get(history.size() - 1);
        PricePoint previous = history.get(history.size() - 2);
        List<AnomalyAlert> alerts = new ArrayList<>();
        BigDecimal priceChange = latest.close().subtract(previous.close())
                .divide(previous.close(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        if (priceChange.abs().compareTo(priceThresholdPercent) >= 0) {
            AnomalyType type = priceChange.signum() >= 0 ? AnomalyType.PRICE_SURGE : AnomalyType.PRICE_DROP;
            alerts.add(new AnomalyAlert(symbol, stockName, type, AnomalySeverity.HIGH, priceChange.abs(),
                    "직전 거래일 대비 가격 " + priceChange.abs().setScale(2, RoundingMode.HALF_UP) + "% "
                            + (type == AnomalyType.PRICE_SURGE ? "상승" : "하락"), Instant.now()));
        }
        double averageVolume = history.subList(0, history.size() - 1).stream().mapToLong(PricePoint::volume).average().orElse(0);
        if (averageVolume > 0) {
            BigDecimal ratio = BigDecimal.valueOf(latest.volume()).divide(BigDecimal.valueOf(averageVolume), 2, RoundingMode.HALF_UP);
            if (ratio.compareTo(volumeThresholdRatio) >= 0) {
                alerts.add(new AnomalyAlert(symbol, stockName, AnomalyType.VOLUME_SPIKE,
                        ratio.compareTo(BigDecimal.valueOf(3)) >= 0 ? AnomalySeverity.HIGH : AnomalySeverity.MEDIUM,
                        ratio, "최근 평균 대비 거래량 " + ratio + "배 증가", Instant.now()));
            }
        }
        return List.copyOf(alerts);
    }
}
