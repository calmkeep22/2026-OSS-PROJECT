package org.ossproject.kiwoom.query;

import org.ossproject.kiwoom.config.KiwoomRestClient;

import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.broker.error.BrokerException;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 키움 모의투자 주문을 애플리케이션 포트에 연결한다.
 *
 * <p>주문의 진짜 상태는 증권사가 들고 있다. 이 어댑터는 상태를 자체적으로 만들지 않고
 * 주문체결현황(kt00009)을 다시 읽어 돌려준다. 앱이 따로 계산한 상태와 증권사 상태가
 * 어긋나면, 화면을 볼 수 없는 사용자는 어느 쪽이 맞는지 확인할 수 없다.
 *
 * <p>조회는 호출 한도가 있어 짧게 캐시한다. 주문을 넣거나 취소한 직후에는 캐시를 버려
 * 바뀐 상태가 곧바로 보이게 한다.
 *
 * <p>주문 전송은 재시도하지 않는다. 응답을 받지 못한 주문을 다시 보내면 중복 체결이 날 수
 * 있다. 실패로 단정하지 않고 그대로 알린다.
 */
public final class KiwoomOrderLifecycleAdapter implements OrderLifecyclePort {

    /** 주문 목록 캐시 유효 시간. 화면을 여는 동안 같은 조회가 반복되는 것을 막는다. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(2);

    private final KiwoomRestClient client;
    private final KiwoomAccountAdapter account;
    private final Clock clock;
    private final Object cacheLock = new Object();
    private List<Order> cachedOrders;
    private Instant cachedAt;

    public KiwoomOrderLifecycleAdapter(KiwoomRestClient client, KiwoomAccountAdapter account, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.account = Objects.requireNonNull(account, "account");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Order submit(OrderCommand command) {
        Objects.requireNonNull(command, "command");
        String brokerOrderId = client.placeOrder(account.accountNo(), command);
        invalidate();
        // 접수 직후의 상태를 증권사에서 다시 확인한다. 아직 목록에 없으면 접수된 것으로 본다.
        return findOrder(brokerOrderId)
                .orElseGet(() -> Order.create(brokerOrderId, command, clock.instant())
                        .accept(clock.instant()));
    }

    @Override
    public Order cancel(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("주문 번호는 필수입니다.");
        }
        // 취소 TR 은 종목코드를 함께 요구한다. 원주문에서 읽어 온다.
        String symbol = findOrder(orderId).map(Order::symbol).orElseThrow(() ->
                new BrokerException("취소할 주문 " + orderId + " 을(를) 찾지 못했습니다."));
        client.cancelOrder(account.accountNo(), orderId, symbol);
        invalidate();
        return findOrder(orderId).orElseThrow(() ->
                new BrokerException("취소한 주문 " + orderId + " 의 상태를 확인하지 못했습니다."));
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return orders().stream().filter(order -> order.orderId().equals(orderId)).findFirst();
    }

    @Override
    public List<Order> openOrders() {
        return orders().stream().filter(order -> !order.status().isTerminal()).toList();
    }

    @Override
    public List<Order> orders() {
        synchronized (cacheLock) {
            Instant now = clock.instant();
            if (cachedOrders != null && cachedAt != null
                    && now.isBefore(cachedAt.plus(CACHE_TTL))) {
                return cachedOrders;
            }
            cachedOrders = client.fetchOrders(account.accountNo());
            cachedAt = now;
            return cachedOrders;
        }
    }

    /** 다음 조회에서 증권사 상태를 다시 읽게 한다. */
    public void invalidate() {
        synchronized (cacheLock) {
            cachedOrders = null;
            cachedAt = null;
        }
    }
}
