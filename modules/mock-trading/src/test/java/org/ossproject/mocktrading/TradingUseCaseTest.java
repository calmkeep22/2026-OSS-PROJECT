package org.ossproject.mocktrading;

import org.junit.jupiter.api.Test;
import org.ossproject.application.policy.OrderGuard;
import org.ossproject.application.policy.OrderLimitPolicy;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingUseCaseTest {
    private final MockTradingEngine engine = new MockTradingEngine(
            DemoTradingAccounts.koreanStocks(), FillMode.MANUAL);
    private final TradingUseCase useCase = new TradingUseCase(
            engine, engine, new OrderGuard(OrderLimitPolicy.unlimited()));

    @Test
    void previewsBuyOrderWithoutSubmittingIt() {
        var command = OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                10, new BigDecimal("73500"));

        var preview = useCase.preview(command, null);

        assertEquals(new BigDecimal("735000"), preview.estimatedAmount());
        assertEquals(new BigDecimal("11765000"), preview.availableCashAfter());
        assertTrue(useCase.orders().isEmpty());
    }

    @Test
    void rejectsPreviewWhenCashIsInsufficient() {
        var command = OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                1_000_000, new BigDecimal("73500"));

        assertThrows(IllegalArgumentException.class, () -> useCase.preview(command, null));
    }

    @Test
    void submitIsExplicitAndReturnsLifecycleOrder() {
        var command = OrderCommand.limit("005930", "삼성전자", OrderSide.SELL,
                1, new BigDecimal("73500"));

        var order = useCase.submitConfirmed(command, null);

        assertTrue(order.orderId().startsWith("MOCK-"));
        assertEquals(OrderStatus.ACCEPTED, order.status());
        assertEquals(order, useCase.findOrder(order.orderId()).orElseThrow());
    }

    @Test
    void exposesTheSameAccountModelUsedByOrderProcessing() {
        assertEquals(3, useCase.account().positions().size());
        assertEquals(new BigDecimal("15518000"), useCase.account().totalAssets());
    }
}
