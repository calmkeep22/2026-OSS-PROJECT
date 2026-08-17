package org.ossproject.application.usecase;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationListener;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Exchange;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketApplicationServiceTest {
    private static final SecurityId SAMSUNG = new SecurityId("005930", Exchange.KRX);

    private final StubStockQuery stocks = new StubStockQuery();
    private final StubCandleQuery candles = new StubCandleQuery();
    private final StubMarketStream stream = new StubMarketStream();
    private final MarketApplicationService service =
            new MarketApplicationService(stocks, candles, stream, Runnable::run);

    @Test
    void delegatesQueriesAndReturnsImmutableResults() {
        List<SecuritySummary> search = service.search(" 삼성 ", 5)
                .toCompletableFuture().join();
        StockDetail detail = service.loadDetail(SAMSUNG).toCompletableFuture().join();
        List<Candle> history = service.loadCandles(SAMSUNG, CandleInterval.DAY, 10)
                .toCompletableFuture().join();

        assertEquals("삼성", stocks.lastQuery);
        assertEquals(5, stocks.lastLimit);
        assertEquals("005930", stocks.lastDetailSymbol);
        assertEquals("005930", candles.lastSymbol);
        assertEquals(CandleInterval.DAY, candles.lastInterval);
        assertEquals(10, candles.lastCount);
        assertEquals("삼성전자", search.get(0).name());
        assertEquals("삼성전자", detail.name());
        assertEquals(1, history.size());
        assertThrows(UnsupportedOperationException.class, () -> search.add(search.get(0)));
        assertThrows(UnsupportedOperationException.class, () -> history.add(history.get(0)));
    }

    @Test
    void invalidLimitsFailWithoutCallingAdapters() {
        CompletionException searchFailure = assertThrows(CompletionException.class,
                () -> service.search("", 0).toCompletableFuture().join());
        CompletionException candleFailure = assertThrows(CompletionException.class,
                () -> service.loadCandles(SAMSUNG, CandleInterval.DAY, -1)
                        .toCompletableFuture().join());

        assertTrue(searchFailure.getCause() instanceof IllegalArgumentException);
        assertTrue(candleFailure.getCause() instanceof IllegalArgumentException);
        assertEquals(0, stocks.searchCalls);
        assertEquals(0, candles.calls);
    }

    @Test
    void monitorFiltersQuotesAndStopsAllCallbacksAfterClose() {
        RecordingListener listener = new RecordingListener();
        EventSubscription subscription = service.monitor(SAMSUNG, listener);

        stream.emit(Quote.of("000660", new BigDecimal("180000"), 1, Instant.now()));
        stream.emit(Quote.of("005930", new BigDecimal("72000"), 2, Instant.now()));

        assertEquals(Set.of("005930"), stream.subscriptions());
        assertEquals(ConnectionState.CONNECTED, listener.states.get(0));
        assertEquals(1, listener.quotes.size());

        subscription.close();
        subscription.close();
        stream.emit(Quote.of("005930", new BigDecimal("72100"), 3, Instant.now()));
        stream.changeState(ConnectionState.RECONNECTING, "안전한 연결 안내");

        assertTrue(stream.subscriptions().isEmpty());
        assertEquals(1, listener.quotes.size());
        assertEquals(1, listener.states.size());
    }

    @Test
    void sameSymbolRemainsSubscribedUntilLastMonitorCloses() {
        EventSubscription first = service.monitor(SAMSUNG, new RecordingListener());
        EventSubscription second = service.monitor(SAMSUNG, new RecordingListener());

        first.close();
        assertEquals(Set.of("005930"), stream.subscriptions());

        second.close();
        assertTrue(stream.subscriptions().isEmpty());
        assertEquals(1, stream.subscribeCalls);
        assertEquals(1, stream.unsubscribeCalls);
    }

    @Test
    void rejectsAmbiguousSameSymbolAcrossExchangesUntilLowLevelPortSupportsIdentity() {
        EventSubscription krx = service.monitor(SAMSUNG, new RecordingListener());

        assertThrows(IllegalStateException.class,
                () -> service.monitor(new SecurityId("005930", Exchange.NXT),
                        new RecordingListener()));
        assertEquals(Set.of("005930"), stream.subscriptions());

        krx.close();
        assertTrue(stream.subscriptions().isEmpty());
    }

    @Test
    void closeIsIdempotentAndRejectsNewWork() {
        service.monitor(SAMSUNG, new RecordingListener());

        service.close();
        service.close();

        assertEquals(1, stream.closeCalls);
        assertTrue(stream.subscriptions().isEmpty());
        assertThrows(CompletionException.class,
                () -> service.search("005930", 1).toCompletableFuture().join());
        assertThrows(IllegalStateException.class,
                () -> service.monitor(SAMSUNG, new RecordingListener()));
    }

    private static final class RecordingListener implements MarketApplicationListener {
        private final List<Quote> quotes = new ArrayList<>();
        private final List<ConnectionState> states = new ArrayList<>();

        @Override
        public void onQuote(Quote quote) {
            quotes.add(quote);
        }

        @Override
        public void onConnectionChanged(ConnectionState state, String safeDetail) {
            states.add(state);
        }
    }

    private static final class StubStockQuery implements StockQueryPort {
        private int searchCalls;
        private String lastQuery;
        private int lastLimit;
        private String lastDetailSymbol;

        @Override
        public List<SecuritySummary> search(String query, int limit) {
            searchCalls++;
            lastQuery = query;
            lastLimit = limit;
            return new ArrayList<>(List.of(summary()));
        }

        @Override
        public StockDetail getDetail(String symbol) {
            lastDetailSymbol = symbol;
            return detail();
        }
    }

    private static final class StubCandleQuery implements CandleQueryPort {
        private int calls;
        private String lastSymbol;
        private CandleInterval lastInterval;
        private int lastCount;

        @Override
        public List<Candle> getCandles(String symbol, CandleInterval interval, int count) {
            calls++;
            lastSymbol = symbol;
            lastInterval = interval;
            lastCount = count;
            return new ArrayList<>(List.of(new Candle(Instant.parse("2026-08-17T00:00:00Z"),
                    interval, new BigDecimal("70000"), new BigDecimal("73000"),
                    new BigDecimal("69000"), new BigDecimal("72000"), 100L)));
        }
    }

    private static final class StubMarketStream implements MarketDataStreamPort {
        private final Set<String> subscriptions = new LinkedHashSet<>();
        private final List<QuoteListener> quoteListeners = new ArrayList<>();
        private final List<ConnectionListener> connectionListeners = new ArrayList<>();
        private ConnectionState state = ConnectionState.DISCONNECTED;
        private int subscribeCalls;
        private int unsubscribeCalls;
        private int closeCalls;

        @Override
        public void connect() {
            changeState(ConnectionState.CONNECTED, null);
        }

        @Override
        public void subscribe(Collection<String> symbols) {
            subscribeCalls++;
            subscriptions.addAll(symbols);
        }

        @Override
        public void unsubscribe(Collection<String> symbols) {
            unsubscribeCalls++;
            subscriptions.removeAll(symbols);
        }

        @Override
        public Set<String> subscriptions() {
            return Set.copyOf(subscriptions);
        }

        @Override
        public void addQuoteListener(QuoteListener listener) {
            quoteListeners.add(listener);
        }

        @Override
        public void removeQuoteListener(QuoteListener listener) {
            quoteListeners.remove(listener);
        }

        @Override
        public void addConnectionListener(ConnectionListener listener) {
            connectionListeners.add(listener);
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
        public void close() {
            closeCalls++;
            subscriptions.clear();
            changeState(ConnectionState.DISCONNECTED, null);
        }

        void emit(Quote quote) {
            List.copyOf(quoteListeners).forEach(listener -> listener.onQuote(quote));
        }

        void changeState(ConnectionState next, String detail) {
            state = next;
            List.copyOf(connectionListeners)
                    .forEach(listener -> listener.onConnectionStateChanged(next, detail));
        }
    }

    private static SecuritySummary summary() {
        return new SecuritySummary("005930", "삼성전자", "국내", "KRX", "KRW",
                new BigDecimal("72000"), new BigDecimal("1.25"), PriceDirection.UP);
    }

    private static StockDetail detail() {
        return new StockDetail("005930", "삼성전자", new BigDecimal("72000"),
                new BigDecimal("1000"), new BigDecimal("1.25"), PriceDirection.UP,
                new BigDecimal("70000"), new BigDecimal("73000"), new BigDecimal("69000"),
                100L, Instant.parse("2026-08-17T00:00:00Z"));
    }
}
