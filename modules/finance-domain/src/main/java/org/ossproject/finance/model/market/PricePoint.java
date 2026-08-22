package org.ossproject.finance.model.market;
import java.math.BigDecimal;
import java.time.LocalDate;
public record PricePoint(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {}
