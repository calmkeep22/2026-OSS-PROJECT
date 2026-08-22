package org.ossproject.finance.model.market;

import org.ossproject.finance.model.PriceDirection;
import java.math.BigDecimal;
import java.time.Instant;
public record StockDetail(String symbol, String name, BigDecimal currentPrice, BigDecimal changeAmount,
                          BigDecimal changeRate, PriceDirection direction, BigDecimal open, BigDecimal high,
                          BigDecimal low, long volume, Instant updatedAt) {}
