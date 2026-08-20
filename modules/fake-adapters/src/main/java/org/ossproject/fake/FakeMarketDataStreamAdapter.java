package org.ossproject.fake;

import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.OrderBookListener;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.application.port.TradeListener;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.Trade;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Controllable in-memory market stream with the same lifecycle as a live adapter. */
public final class FakeMarketDataStreamAdapter implements MarketDataStreamPort {
    private final Set<String> subscriptions = new LinkedHashSet<>();
    private final List<QuoteListener> quoteListeners = new CopyOnWriteArrayList<>();
    private final List<OrderBookListener> orderBookListeners = new CopyOnWriteArrayList<>();
    private final List<TradeListener> tradeListeners = new CopyOnWriteArrayList<>();
    private final List<ConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    private ConnectionState state = ConnectionState.DISCONNECTED;
    private boolean closed;

    @Override
    public synchronized void connect() {
        if (closed) throw new IllegalStateException("Closed stream cannot be reconnected.");
        changeState(ConnectionState.CONNECTED, null);
    }

    @Override
    public synchronized void subscribe(Collection<String> symbols) {
        if (symbols == null) return;
        symbols.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(symbol -> !symbol.isEmpty()).forEach(subscriptions::add);
    }

    @Override
    public synchronized void unsubscribe(Collection<String> symbols) {
        if (symbols != null) subscriptions.removeAll(symbols);
    }

    @Override
    public synchronized Set<String> subscriptions() {
        return Set.copyOf(subscriptions);
    }

    @Override
    public void addQuoteListener(QuoteListener listener) {
        if (listener != null) quoteListeners.add(listener);
    }

    @Override
    public void removeQuoteListener(QuoteListener listener) {
        quoteListeners.remove(listener);
    }

    @Override
    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null) connectionListeners.add(listener);
    }

    @Override
    public void removeConnectionListener(ConnectionListener listener) {
        connectionListeners.remove(listener);
    }

    @Override
    public synchronized ConnectionState connectionState() {
        return state;
    }

    @Override
    public boolean supportsOrderBook() {
        return true;
    }

    @Override
    public void addOrderBookListener(OrderBookListener listener) {
        if (listener != null) orderBookListeners.add(listener);
    }

    @Override
    public void removeOrderBookListener(OrderBookListener listener) {
        orderBookListeners.remove(listener);
    }

    @Override
    public boolean supportsTrades() {
        return true;
    }

    @Override
    public void addTradeListener(TradeListener listener) {
        if (listener != null) tradeListeners.add(listener);
    }

    @Override
    public void removeTradeListener(TradeListener listener) {
        tradeListeners.remove(listener);
    }

    /** Emits a trade only when connected and subscribed to its symbol. */
    public void emitTrade(Trade trade) {
        if (trade == null) throw new IllegalArgumentException("Trade is required.");
        synchronized (this) {
            if (state != ConnectionState.CONNECTED || !subscriptions.contains(trade.symbol())) return;
        }
        tradeListeners.forEach(listener -> listener.onTrade(trade));
    }

    /** Emits an order book only when connected and subscribed to its symbol. */
    public void emitOrderBook(OrderBook book) {
        if (book == null) throw new IllegalArgumentException("Order book is required.");
        synchronized (this) {
            if (state != ConnectionState.CONNECTED || !subscriptions.contains(book.symbol())) return;
        }
        orderBookListeners.forEach(listener -> listener.onOrderBook(book));
    }

    /** Emits a quote only when connected and subscribed to its symbol. */
    public void emit(Quote quote) {
        if (quote == null) throw new IllegalArgumentException("Quote is required.");
        synchronized (this) {
            if (state != ConnectionState.CONNECTED || !subscriptions.contains(quote.symbol())) return;
        }
        quoteListeners.forEach(listener -> listener.onQuote(quote));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        subscriptions.clear();
        orderBookListeners.clear();
        tradeListeners.clear();
        changeState(ConnectionState.DISCONNECTED, null);
    }

    private void changeState(ConnectionState next, String detail) {
        if (state == next) return;
        state = next;
        connectionListeners.forEach(listener -> listener.onConnectionStateChanged(next, detail));
    }
}
