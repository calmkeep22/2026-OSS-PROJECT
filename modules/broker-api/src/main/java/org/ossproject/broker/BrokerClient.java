package org.ossproject.broker;

import org.ossproject.finance.model.account.Account;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.order.Order;
import org.ossproject.finance.model.order.OrderCommand;
import org.ossproject.finance.model.market.Quote;

import java.util.List;

/** Synchronous broker boundary shared by concrete securities-company adapters. */
public interface BrokerClient extends AutoCloseable {
    String brokerId();

    void authenticate();

    boolean isAuthenticated();

    Account fetchAccount(String accountNo);

    Quote fetchQuote(String symbol);

    List<Candle> fetchCandles(String symbol, CandleInterval interval, int count);

    /** Submits an order and returns the broker-issued order id. */
    String placeOrder(String accountNo, OrderCommand command);

    void cancelOrder(String accountNo, String brokerOrderId);

    List<Order> fetchOrders(String accountNo);

    @Override
    void close();
}
