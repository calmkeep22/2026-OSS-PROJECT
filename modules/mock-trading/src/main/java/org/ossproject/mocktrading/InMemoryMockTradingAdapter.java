package org.ossproject.mocktrading;

import org.ossproject.finance.model.*;
import org.ossproject.application.port.OrderPort;
import org.ossproject.application.port.PortfolioPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class InMemoryMockTradingAdapter implements PortfolioPort, OrderPort {
    private final PortfolioSnapshot snapshot = new PortfolioSnapshot(
            new BigDecimal("12500000"), List.of(
            new Holding("005930", "삼성전자", 20, new BigDecimal("71000"), new BigDecimal("73500")),
            new Holding("000660", "SK하이닉스", 5, new BigDecimal("183000"), new BigDecimal("190500")),
            new Holding("035420", "NAVER", 3, new BigDecimal("204000"), new BigDecimal("198500"))));

    @Override public PortfolioSnapshot getPortfolio() { return snapshot; }

    @Override public OrderPreview preview(OrderRequest request) {
        BigDecimal amount = request.estimatedAmount();
        BigDecimal cashAfter = request.side() == OrderSide.BUY
                ? snapshot.cash().subtract(amount) : snapshot.cash().add(amount);
        if (request.side() == OrderSide.BUY && cashAfter.signum() < 0) {
            throw new IllegalArgumentException("주문 가능 현금이 부족합니다.");
        }
        return new OrderPreview(request, amount, cashAfter);
    }

    @Override public OrderReceipt submit(OrderRequest request) {
        preview(request);
        return new OrderReceipt("MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                request.name() + " " + request.quantity() + "주 " + request.side().displayName() + " 모의주문이 접수되었습니다.");
    }
}
