package org.ossproject.anomaly;

import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.PricePoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleBasedAnomalyDetectorTest {
    @Test void detectsPriceDropAndVolumeSpike() {
        var history = List.of(
                point("100", 100), point("101", 100), point("90", 400));
        var alerts = new RuleBasedAnomalyDetector().detect("000001", "테스트", history);
        assertEquals(2, alerts.size());
        assertEquals(AnomalyType.PRICE_DROP, alerts.get(0).type());
        assertEquals(AnomalyType.VOLUME_SPIKE, alerts.get(1).type());
    }
    private PricePoint point(String close, long volume) {
        var price = new BigDecimal(close);
        return new PricePoint(LocalDate.now(), price, price, price, price, volume);
    }
}
