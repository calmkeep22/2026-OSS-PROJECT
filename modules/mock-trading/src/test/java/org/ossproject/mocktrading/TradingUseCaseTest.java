package org.ossproject.mocktrading;

import org.junit.jupiter.api.Test;
import org.ossproject.application.policy.OrderGuard;
import org.ossproject.application.policy.OrderLimitPolicy;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.Position;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingUseCaseTest {

    /**
     * 매도와 잔고를 확인하기 위한 계좌.
     *
     * <p>모의투자는 보유 종목 없이 시작하므로, 보유분이 필요한 검증은 테스트가 직접 만든다.
     */
    private static Account accountWithHoldings() {
        return new Account("00000000001", Balance.of(new BigDecimal("12500000")), List.of(
                new Position("005930", "삼성전자", 20, 0,
                        new BigDecimal("71000"), new BigDecimal("73500")),
                new Position("000660", "SK하이닉스", 5, 0,
                        new BigDecimal("183000"), new BigDecimal("190500")),
                new Position("035420", "NAVER", 3, 0,
                        new BigDecimal("204000"), new BigDecimal("198500"))));
    }

    private final MockTradingEngine engine = new MockTradingEngine(
            accountWithHoldings(), FillMode.MANUAL);
    private final TradingUseCase useCase = new TradingUseCase(
            engine, engine, new OrderGuard(OrderLimitPolicy.unlimited()));

    @Test
    void mockAccountStartsWithCashOnly() {
        var fresh = DemoTradingAccounts.koreanStocks();

        assertTrue(fresh.positions().isEmpty(), "사지 않은 종목이 보유로 잡히면 안 됩니다");
        assertEquals(new BigDecimal("12500000"), fresh.balance().cash());
    }

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
