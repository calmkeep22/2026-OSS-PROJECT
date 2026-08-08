package org.ossproject.mocktrading;

import org.ossproject.application.port.AccountPort;
import org.ossproject.application.port.OrderEventListener;
import org.ossproject.application.port.OrderEventSource;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.Execution;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.OrderType;
import org.ossproject.finance.model.Position;
import org.ossproject.finance.model.Quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 상태를 가진 모의주문 엔진.
 *
 * <p>실계좌 없이도 주문 흐름 전체를 그대로 재현한다. 주문을 접수하면 예수금이나 보유 수량을
 * 잡아 두고, 체결되면 잔고와 보유 종목이 실제로 바뀌며, 취소하면 잡아 둔 자원이 되돌아온다.
 *
 * <p>모든 상태 변경은 인스턴스 락 안에서 처리하고, 리스너 호출은 락을 놓은 뒤에 한다.
 * 리스너가 다시 엔진을 호출해도 교착이 생기지 않는다.
 */
public final class MockTradingEngine
        implements OrderLifecyclePort, AccountPort, OrderEventSource, QuoteListener {

    private static final String DEFAULT_ACCOUNT_NO = "00000000001";

    private final Clock clock;
    private final FillMode fillMode;
    private final AtomicLong sequence = new AtomicLong();
    private final List<OrderEventListener> listeners = new CopyOnWriteArrayList<>();

    /** 접수 순서를 유지하기 위해 LinkedHashMap 을 쓴다. */
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Map<String, BigDecimal> lastPrices = new HashMap<>();

    private Account account;

    /**
     * 주문 접수 시 잡아 둔 자원.
     *
     * @param cash      매수 주문이 잡아 둔 남은 금액
     * @param unitPrice 잡을 때 사용한 주당 가격. 체결 시 풀어 줄 금액을 계산한다
     * @param shares    매도 주문이 잡아 둔 남은 수량
     */
    private record Reservation(BigDecimal cash, BigDecimal unitPrice, long shares) {
    }

    public MockTradingEngine(Account initialAccount, FillMode fillMode, Clock clock) {
        if (initialAccount == null) {
            throw new IllegalArgumentException("초기 계좌는 필수입니다.");
        }
        if (fillMode == null) {
            throw new IllegalArgumentException("체결 방식은 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.account = initialAccount;
        this.fillMode = fillMode;
        this.clock = clock;
        for (Position position : initialAccount.positions()) {
            lastPrices.put(position.symbol(), position.currentPrice());
        }
    }

    public MockTradingEngine(Account initialAccount, FillMode fillMode) {
        this(initialAccount, fillMode, Clock.systemDefaultZone());
    }

    /** 화면 시연에 쓰는 기본 계좌. 예수금만 있고 보유 종목은 없다. */
    public static MockTradingEngine withCash(BigDecimal cash, FillMode fillMode) {
        return new MockTradingEngine(Account.of(DEFAULT_ACCOUNT_NO, cash), fillMode);
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Override
    public synchronized Account getAccount() {
        return account;
    }

    @Override
    public synchronized Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public synchronized List<Order> orders() {
        List<Order> snapshot = new ArrayList<>(orders.values());
        Collections.reverse(snapshot);
        return List.copyOf(snapshot);
    }

    @Override
    public synchronized List<Order> openOrders() {
        return orders().stream().filter(order -> !order.isTerminal()).toList();
    }

    /** 마지막으로 관측된 시세. 시장가 주문 금액 계산에 쓴다. */
    public synchronized Optional<BigDecimal> lastPrice(String symbol) {
        return Optional.ofNullable(lastPrices.get(symbol));
    }

    // ------------------------------------------------------------------
    // 초기 상태 설정
    // ------------------------------------------------------------------

    /** 시세 스트림 없이 기준 가격을 넣어 둔다. */
    public synchronized void seedPrice(String symbol, BigDecimal price) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
        }
        lastPrices.put(symbol, price);
    }

    // ------------------------------------------------------------------
    // 주문
    // ------------------------------------------------------------------

    @Override
    public Order submit(OrderCommand command) {
        List<Runnable> events = new ArrayList<>();
        Order result;
        synchronized (this) {
            result = doSubmit(command, events);
        }
        publish(events);
        return result;
    }

    @Override
    public Order cancel(String orderId) {
        List<Runnable> events = new ArrayList<>();
        Order result;
        synchronized (this) {
            result = doCancel(orderId, events);
        }
        publish(events);
        return result;
    }

    /** 증권사가 주문을 거부한 상황을 재현한다. */
    public Order reject(String orderId, String reason) {
        List<Runnable> events = new ArrayList<>();
        Order result;
        synchronized (this) {
            Order order = requireOrder(orderId);
            releaseReservation(order);
            result = order.reject(reason, clock.instant());
            orders.put(orderId, result);
            Order published = result;
            events.add(() -> notifyOrderUpdated(published));
        }
        publish(events);
        return result;
    }

    /**
     * 체결을 직접 발생시킨다. {@link FillMode#MANUAL} 에서 체결 시나리오를 만들 때 쓴다.
     *
     * @throws IllegalStateException 이미 종료된 주문이거나 남은 수량을 넘는 경우
     */
    public Order fill(String orderId, long quantity, BigDecimal price) {
        List<Runnable> events = new ArrayList<>();
        Order result;
        synchronized (this) {
            Order order = requireOrder(orderId);
            if (order.status() == OrderStatus.NEW) {
                order = accept(order, events);
            }
            result = applyFill(order, quantity, price, events);
        }
        publish(events);
        return result;
    }

    private Order doSubmit(OrderCommand command, List<Runnable> events) {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        BigDecimal unitPrice = resolveUnitPrice(command);
        Instant now = clock.instant();
        Order order = Order.create(nextOrderId(), command, now);

        reserve(order, unitPrice);
        orders.put(order.orderId(), order);

        Order accepted = accept(order, events);

        boolean fillNow = fillMode == FillMode.IMMEDIATE
                || (fillMode == FillMode.ON_QUOTE && command.type() == OrderType.MARKET);
        if (fillNow) {
            return applyFill(accepted, accepted.remainingQuantity(), unitPrice, events);
        }
        return accepted;
    }

    private Order doCancel(String orderId, List<Runnable> events) {
        Order order = requireOrder(orderId);
        if (order.isTerminal()) {
            throw new IllegalStateException(
                    "이미 종료된 주문은 취소할 수 없습니다. 현재 상태 " + order.status().displayName());
        }
        releaseReservation(order);
        Order cancelled = order.cancel(clock.instant());
        orders.put(orderId, cancelled);
        events.add(() -> notifyOrderUpdated(cancelled));
        return cancelled;
    }

    private Order accept(Order order, List<Runnable> events) {
        Order accepted = order.accept(clock.instant());
        orders.put(accepted.orderId(), accepted);
        events.add(() -> notifyOrderUpdated(accepted));
        return accepted;
    }

    /** 주문 접수 시 예수금이나 보유 수량을 잡아 둔다. */
    private void reserve(Order order, BigDecimal unitPrice) {
        if (order.side() == OrderSide.BUY) {
            BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(order.quantity()));
            account = account.withBalance(account.balance().lock(amount));
            reservations.put(order.orderId(), new Reservation(amount, unitPrice, 0L));
        } else {
            Position position = account.position(order.symbol()).orElseThrow(
                    () -> new IllegalStateException("보유하지 않은 종목은 매도할 수 없습니다. " + order.symbol()));
            account = account.withPosition(position.lock(order.quantity()));
            reservations.put(order.orderId(), new Reservation(BigDecimal.ZERO, unitPrice, order.quantity()));
        }
    }

    /** 취소·거부·종료 시 남은 예약을 되돌린다. */
    private void releaseReservation(Order order) {
        Reservation reservation = reservations.remove(order.orderId());
        if (reservation == null) {
            return;
        }
        if (order.side() == OrderSide.BUY) {
            if (reservation.cash().signum() > 0) {
                account = account.withBalance(account.balance().unlock(reservation.cash()));
            }
        } else if (reservation.shares() > 0) {
            account.position(order.symbol())
                    .ifPresent(position -> account = account.withPosition(position.unlock(reservation.shares())));
        }
    }

    private Order applyFill(Order order, long quantity, BigDecimal price, List<Runnable> events) {
        Execution execution = new Execution(nextExecutionId(), order.orderId(), order.symbol(),
                order.side(), quantity, price, clock.instant());
        Order updated = order.applyExecution(execution);

        settle(order, execution);

        orders.put(updated.orderId(), updated);
        if (updated.isTerminal()) {
            // 지정가보다 싸게 체결되어 남은 예약이 있으면 여기서 모두 되돌린다.
            releaseReservation(updated);
        }

        events.add(() -> notifyExecution(execution));
        events.add(() -> notifyOrderUpdated(updated));
        return updated;
    }

    /** 체결 한 건을 잔고와 보유 종목에 반영한다. */
    private void settle(Order order, Execution execution) {
        Reservation reservation = reservations.get(order.orderId());
        if (reservation == null) {
            throw new IllegalStateException("예약 정보가 없는 주문의 체결입니다. " + order.orderId());
        }

        if (order.side() == OrderSide.BUY) {
            BigDecimal releaseTarget = reservation.unitPrice()
                    .multiply(BigDecimal.valueOf(execution.quantity()));
            BigDecimal release = releaseTarget.min(reservation.cash());
            Balance balance = account.balance();
            if (release.signum() > 0) {
                balance = balance.unlock(release);
            }
            account = account.withBalance(balance.withdraw(execution.amount()));

            Position position = account.position(order.symbol())
                    .map(existing -> existing.addShares(execution.quantity(), execution.price()))
                    .orElseGet(() -> Position.of(order.symbol(), order.name(),
                            execution.quantity(), execution.price()));
            account = account.withPosition(position.withCurrentPrice(execution.price()));

            reservations.put(order.orderId(), new Reservation(
                    reservation.cash().subtract(release), reservation.unitPrice(), 0L));
        } else {
            Position position = account.position(order.symbol()).orElseThrow(
                    () -> new IllegalStateException("보유하지 않은 종목의 매도 체결입니다. " + order.symbol()));
            Position afterUnlock = position.unlock(Math.min(execution.quantity(), position.lockedQuantity()));
            account = account.withPosition(afterUnlock.removeShares(execution.quantity()));
            account = account.withBalance(account.balance().deposit(execution.amount()));

            reservations.put(order.orderId(), new Reservation(BigDecimal.ZERO, reservation.unitPrice(),
                    Math.max(0L, reservation.shares() - execution.quantity())));
        }

        lastPrices.put(order.symbol(), execution.price());
    }

    private BigDecimal resolveUnitPrice(OrderCommand command) {
        if (command.type() == OrderType.LIMIT) {
            return command.limitPrice();
        }
        BigDecimal last = lastPrices.get(command.symbol());
        if (last == null) {
            throw new IllegalStateException(
                    "시장가 주문을 처리하려면 기준 시세가 필요합니다. 종목 " + command.symbol());
        }
        return last;
    }

    // ------------------------------------------------------------------
    // 실시간 시세
    // ------------------------------------------------------------------

    /**
     * 실시간 시세를 반영한다. 보유 종목의 평가액을 갱신하고,
     * {@link FillMode#ON_QUOTE} 이면 지정가를 지나간 미체결 주문을 체결한다.
     */
    @Override
    public void onQuote(Quote quote) {
        if (quote == null) {
            return;
        }
        List<Runnable> events = new ArrayList<>();
        synchronized (this) {
            lastPrices.put(quote.symbol(), quote.price());
            account = account.applyQuote(quote);
            if (fillMode == FillMode.ON_QUOTE) {
                matchOpenOrders(quote, events);
            }
        }
        publish(events);
    }

    private void matchOpenOrders(Quote quote, List<Runnable> events) {
        // applyFill 이 orders 를 수정하므로 스냅샷을 떠서 순회한다.
        for (Order order : new ArrayList<>(orders.values())) {
            if (order.isTerminal() || !order.symbol().equals(quote.symbol())
                    || order.type() != OrderType.LIMIT) {
                continue;
            }
            boolean crossed = order.side() == OrderSide.BUY
                    ? quote.price().compareTo(order.limitPrice()) <= 0
                    : quote.price().compareTo(order.limitPrice()) >= 0;
            if (crossed) {
                applyFill(order, order.remainingQuantity(), quote.price(), events);
            }
        }
    }

    // ------------------------------------------------------------------
    // 이벤트
    // ------------------------------------------------------------------

    @Override
    public void addOrderEventListener(OrderEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeOrderEventListener(OrderEventListener listener) {
        listeners.remove(listener);
    }

    private void publish(List<Runnable> events) {
        for (Runnable event : events) {
            event.run();
        }
    }

    /** 한 리스너가 실패해도 나머지 리스너는 계속 통지받아야 한다. */
    private void notifyOrderUpdated(Order order) {
        for (OrderEventListener listener : listeners) {
            try {
                listener.onOrderUpdated(order);
            } catch (RuntimeException ignored) {
                // 화면 리스너의 실패가 주문 처리를 막지 않도록 삼킨다.
            }
        }
    }

    private void notifyExecution(Execution execution) {
        for (OrderEventListener listener : listeners) {
            try {
                listener.onExecution(execution);
            } catch (RuntimeException ignored) {
                // 위와 같다.
            }
        }
    }

    private Order requireOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new NoSuchElementException("주문을 찾을 수 없습니다. " + orderId);
        }
        return order;
    }

    private String nextOrderId() {
        return String.format("MOCK-%08X", sequence.incrementAndGet());
    }

    private String nextExecutionId() {
        return String.format("EXEC-%08X", sequence.incrementAndGet());
    }
}
