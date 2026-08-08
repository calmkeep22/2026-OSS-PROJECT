package org.ossproject.mocktrading;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.PortfolioUseCase;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioUseCaseTest {
    @Test
    void calculatesTotalPortfolioValue() {
        var portfolio = new PortfolioUseCase(new InMemoryMockTradingAdapter()).loadPortfolio();
        assertEquals(3, portfolio.holdings().size());
        assertEquals(new BigDecimal("15518000"), portfolio.totalMarketValue());
    }
}
