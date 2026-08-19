package org.ossproject.desktop.composition;

import org.ossproject.application.port.AccountPort;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 */
final class UnavailableMarketData
        implements StockQueryPort, CandleQueryPort, AccountPort, OrderLifecyclePort {

    private final String reason;

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

    private IllegalStateException unavailable() {
        return new IllegalStateException(reason);
    }
}
