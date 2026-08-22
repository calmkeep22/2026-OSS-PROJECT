package org.ossproject.mocktrading;

import org.ossproject.application.contract.OrderLifecyclePortContract;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.finance.model.order.OrderCommand;
import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;

class MockTradingOrderContractTest extends OrderLifecyclePortContract {
    @Override
    protected OrderLifecyclePort createOrderPort() {
        return new MockTradingEngine(DemoTradingAccounts.koreanStocks(), FillMode.MANUAL);
    }

    @Override
    protected OrderCommand validOrder() {
        return OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                1, new BigDecimal("73500"));
    }
}
