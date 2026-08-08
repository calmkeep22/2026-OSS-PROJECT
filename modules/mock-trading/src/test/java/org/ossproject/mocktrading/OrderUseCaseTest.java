package org.ossproject.mocktrading;

import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.OrderRequest;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.application.usecase.OrderUseCase;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OrderUseCaseTest {
    private final OrderUseCase useCase = new OrderUseCase(new InMemoryMockTradingAdapter());

    @Test
    void previewsBuyOrderWithoutSubmittingIt() {
        var request = new OrderRequest("005930", "삼성전자", OrderSide.BUY, 10, new BigDecimal("73500"));
        var preview = useCase.preview(request);
        assertEquals(new BigDecimal("735000"), preview.estimatedAmount());
        assertEquals(new BigDecimal("11765000"), preview.estimatedCashAfterOrder());
    }

    @Test
    void rejectsBuyOrderWhenCashIsInsufficient() {
        var request = new OrderRequest("005930", "삼성전자", OrderSide.BUY, 1_000_000, new BigDecimal("73500"));
        var error = assertThrows(IllegalArgumentException.class, () -> useCase.preview(request));
        assertEquals("주문 가능 현금이 부족합니다.", error.getMessage());
    }

    @Test
    void submitIsExplicitAndReturnsFakeReceipt() {
        var request = new OrderRequest("005930", "삼성전자", OrderSide.SELL, 1, new BigDecimal("73500"));
        var receipt = useCase.submitConfirmed(request);
        assertTrue(receipt.orderId().startsWith("MOCK-"));
        assertTrue(receipt.statusMessage().contains("매도 모의주문이 접수되었습니다"));
    }
}
