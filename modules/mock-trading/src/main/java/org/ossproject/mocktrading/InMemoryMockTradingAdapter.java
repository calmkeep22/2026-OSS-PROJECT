package org.ossproject.mocktrading;

import org.ossproject.application.port.OrderPort;
import org.ossproject.application.port.PortfolioPort;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderPreview;
import org.ossproject.finance.model.OrderReceipt;
import org.ossproject.finance.model.OrderRequest;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.PortfolioSnapshot;
import org.ossproject.finance.model.Position;

import java.math.BigDecimal;
import java.util.List;

/**
 * 화면 계층이 쓰는 모의주문 어댑터.
 *
 * <p>겉으로 보이는 동작은 그대로 두고 속을 {@link MockTradingEngine} 으로 바꿨다.
 * 이제 주문을 넣으면 잔고가 실제로 묶이고, 체결·취소가 계좌에 반영된다.
 * 더 자세한 주문 상태와 체결 이벤트가 필요하면 {@link #engine()} 을 쓴다.
 *
 * <p>체결 방식은 {@link FillMode#MANUAL} 이다. 접수까지만 처리하고 체결은 일으키지 않는다.
 */
public final class InMemoryMockTradingAdapter implements PortfolioPort, OrderPort {

    private static final String DEMO_ACCOUNT_NO = "00000000001";

    private final MockTradingEngine engine;

    public InMemoryMockTradingAdapter() {
        this(demoAccount(), FillMode.MANUAL);
    }

    public InMemoryMockTradingAdapter(Account initialAccount, FillMode fillMode) {
        this.engine = new MockTradingEngine(initialAccount, fillMode);
    }

    /** 시연용 초기 계좌. 예수금과 보유 종목 세 개를 가진다. */
    public static Account demoAccount() {
        return new Account(DEMO_ACCOUNT_NO, Balance.of(new BigDecimal("12500000")), List.of(
                new Position("005930", "삼성전자", 20, 0,
                        new BigDecimal("71000"), new BigDecimal("73500")),
                new Position("000660", "SK하이닉스", 5, 0,
                        new BigDecimal("183000"), new BigDecimal("190500")),
                new Position("035420", "NAVER", 3, 0,
                        new BigDecimal("204000"), new BigDecimal("198500"))));
    }

    /** 주문 상태·체결 이벤트·실시간 시세 반영이 필요한 계층은 엔진을 직접 쓴다. */
    public MockTradingEngine engine() {
        return engine;
    }

    @Override
    public PortfolioSnapshot getPortfolio() {
        return engine.getAccount().toSnapshot();
    }

    @Override
    public OrderPreview preview(OrderRequest request) {
        BigDecimal amount = request.estimatedAmount();
        BigDecimal available = engine.getAccount().balance().available();
        BigDecimal cashAfter = request.side() == OrderSide.BUY
                ? available.subtract(amount)
                : available.add(amount);
        if (request.side() == OrderSide.BUY && cashAfter.signum() < 0) {
            throw new IllegalArgumentException("주문 가능 현금이 부족합니다.");
        }
        return new OrderPreview(request, amount, cashAfter);
    }

    @Override
    public OrderReceipt submit(OrderRequest request) {
        preview(request);
        Order order = engine.submit(OrderCommand.from(request));
        return new OrderReceipt(order.orderId(),
                request.name() + " " + request.quantity() + "주 "
                        + request.side().displayName() + " 모의주문이 접수되었습니다.");
    }
}
