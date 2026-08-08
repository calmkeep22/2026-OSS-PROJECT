package org.ossproject.anomaly;
import java.math.BigDecimal;
import java.time.Instant;
public record AnomalyAlert(String symbol, String stockName, AnomalyType type, AnomalySeverity severity,
                           BigDecimal observedRatio, String explanation, Instant detectedAt) {}
