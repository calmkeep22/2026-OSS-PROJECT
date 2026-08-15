package org.ossproject.application.usecase;

import org.ossproject.application.policy.OrderGuard;
import org.ossproject.application.port.AccountPort;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.TradePreview;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 주문 미리보기와 접수를 묶는 유스케이스.
 *
 * <p>주문 흐름은 {@code 미리보기 → 사용자 음성 확인 → 접수} 이다. {@link #preview} 로 만든
 * 문장을 읽어 주고, 사용자가 최종 승인한 뒤에만 {@link #submitConfirmed} 를 호출한다.
 * 안전장치 검사는 접수 시점에만 수행하므로 미리보기는 한도를 소모하지 않는다.
 */
public final class TradingUseCase {

    private final OrderLifecyclePort orderPort;
    private final AccountPort accountPort;
    private final OrderGuard guard;

    public TradingUseCase(OrderLifecyclePort orderPort, AccountPort accountPort, OrderGuard guard) {
        if (orderPort == null) {
            throw new IllegalArgumentException("주문 포트는 필수입니다.");
        }
        if (accountPort == null) {
            throw new IllegalArgumentException("계좌 포트는 필수입니다.");
        }
        if (guard == null) {
            throw new IllegalArgumentException("주문 안전장치는 필수입니다.");
        }
        this.orderPort = orderPort;
        this.accountPort = accountPort;
        this.guard = guard;
    }

    /**
     * 주문을 접수하기 전에 보여 줄 미리보기를 만든다. 아무것도 변경하지 않는다.
     *
     * @param referencePrice 시장가 주문의 예상 금액 계산에 쓸 현재가. 지정가면 무시된다
     */
    public TradePreview preview(OrderCommand command, BigDecimal referencePrice) {
        BigDecimal estimatedAmount = command.estimatedAmount(referencePrice);
        Account account = accountPort.getAccount();
        BigDecimal availableBefore = account.balance().available();
        // 매도 대금은 체결된 뒤에 들어오므로 접수 시점의 주문가능금액은 그대로다.
        BigDecimal availableAfter = availableBefore;
        if (command.side() == OrderSide.BUY) {
            availableAfter = availableBefore.subtract(estimatedAmount);
            if (availableAfter.signum() < 0) {
                throw new IllegalArgumentException("주문 가능 현금이 부족합니다.");
            }
        } else {
            long availableQuantity = account.position(command.symbol())
                    .map(position -> position.availableQuantity())
                    .orElse(0L);
            if (availableQuantity < command.quantity()) {
                throw new IllegalArgumentException("매도 가능 수량이 부족합니다.");
            }
        }
        return new TradePreview(command, referencePrice, estimatedAmount, availableBefore, availableAfter);
    }

    /**
     * 사용자가 최종 승인한 주문을 접수한다.
     *
     * @throws org.ossproject.application.policy.OrderRejectedException 안전장치에 걸린 경우
     * @throws IllegalStateException                                    잔고·보유 수량이 부족한 경우
     */
    public Order submitConfirmed(OrderCommand command, BigDecimal referencePrice) {
        BigDecimal estimatedAmount = command.estimatedAmount(referencePrice);
        guard.authorize(command, estimatedAmount);
        return orderPort.submit(command);
    }

    public Order cancel(String orderId) {
        return orderPort.cancel(orderId);
    }

    public Optional<Order> findOrder(String orderId) {
        return orderPort.findOrder(orderId);
    }

    public List<Order> openOrders() {
        return orderPort.openOrders();
    }

    public List<Order> orders() {
        return orderPort.orders();
    }

    public Account account() {
        return accountPort.getAccount();
    }
}
