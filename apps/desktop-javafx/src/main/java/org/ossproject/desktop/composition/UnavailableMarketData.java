package org.ossproject.desktop.composition;

import org.ossproject.application.port.AccountPort;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.OrderBookQueryPort;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 증권사에 연결되지 않았을 때 쓰는 시세 조회 구현.
 *
 * <p>자격증명이 없으면 화면 개발용 가짜 시세를 대신 넣지 않는다. 가짜 값은 실제 시세와
 * 똑같이 생겨서, 화면을 볼 수 없는 사용자는 지금 듣고 있는 가격이 실제 시장 값인지 구분할
 * 수 없다. 빈 화면보다 지어낸 숫자가 위험하다.
 *
 * <p>대신 모든 조회를 같은 이유로 실패시킨다. 화면은 이미 조회 실패를 사용자에게 알리도록
 * 되어 있으므로, 어디서 실패해도 이유가 전달된다.
 *
 * <p>가짜 어댑터는 테스트와 화면 개발에서 계속 쓴다. 다만 실행 중인 앱에서 실제 시세인 척
 * 하지 않게 한다.
 *
 * <p>실시간 스트림도 같은 원칙을 따른다. 조용히 아무것도 보내지 않으면 사용자는 시세가
 * 멈춘 것인지 연결이 안 된 것인지 구분할 수 없다. 연결을 시도하면 곧바로 실패 상태와
 * 이유를 알린다.
 */
final class UnavailableMarketData
        implements StockQueryPort, CandleQueryPort, AccountPort, OrderLifecyclePort,
        OrderBookQueryPort, MarketDataStreamPort {

    private final String reason;
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private final Set<String> subscriptions = new LinkedHashSet<>();
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;

    UnavailableMarketData(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public List<SecuritySummary> search(String query, int limit) {
        throw unavailable();
    }

    @Override
    public StockDetail getDetail(String symbol) {
        throw unavailable();
    }

    @Override
    public List<Candle> getCandles(String symbol, CandleInterval interval, int count) {
        throw unavailable();
    }

    @Override
    public Account getAccount() {
        throw unavailable();
    }

    @Override
    public OrderBook getOrderBook(String symbol) {
        throw unavailable();
    }

    @Override
    public Order submit(OrderCommand command) {
        throw unavailable();
    }

    @Override
    public Order cancel(String orderId) {
        throw unavailable();
    }

    @Override
    public Optional<Order> findOrder(String orderId) {
        throw unavailable();
    }

    @Override
    public List<Order> openOrders() {
        throw unavailable();
    }

    @Override
    public List<Order> orders() {
        throw unavailable();
    }

    // ------------------------------------------------------------------
    // 실시간 스트림
    // ------------------------------------------------------------------

    /** 연결할 곳이 없다. 조용히 있지 않고 실패와 이유를 알린다. */
    @Override
    public void connect() {
        state = ConnectionState.FAILED;
        for (ConnectionListener listener : connectionListeners) {
            listener.onConnectionStateChanged(state, reason);
        }
    }

    /**
     * 구독 목록은 기억하되 시세는 오지 않는다.
     *
     * <p>예외를 던지면 종목 상세 화면 진입 자체가 막힌다. 시세를 못 받는 것과 화면을 열지
     * 못하는 것은 다른 문제다. 연결 실패는 이미 상태로 알렸다.
     */
    @Override
    public synchronized void subscribe(Collection<String> symbols) {
        if (symbols == null) {
            return;
        }
        for (String symbol : symbols) {
            if (symbol != null && !symbol.isBlank()) {
                subscriptions.add(symbol);
            }
        }
    }

    @Override
    public synchronized void unsubscribe(Collection<String> symbols) {
        if (symbols != null) {
            symbols.forEach(subscriptions::remove);
        }
    }

    @Override
    public synchronized Set<String> subscriptions() {
        return Set.copyOf(subscriptions);
    }

    @Override
    public void addQuoteListener(QuoteListener listener) {
    }

    @Override
    public void removeQuoteListener(QuoteListener listener) {
    }

    @Override
    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    @Override
    public ConnectionState connectionState() {
        return state;
    }

    @Override
    public synchronized void close() {
        state = ConnectionState.DISCONNECTED;
        subscriptions.clear();
        connectionListeners.clear();
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException(reason);
    }
}
