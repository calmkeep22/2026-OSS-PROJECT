package org.ossproject.finance.model;

import java.math.BigDecimal;

public record OrderPreview(OrderRequest request, BigDecimal estimatedAmount, BigDecimal estimatedCashAfterOrder) {}
