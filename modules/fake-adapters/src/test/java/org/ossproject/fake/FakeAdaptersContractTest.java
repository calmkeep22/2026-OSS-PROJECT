package org.ossproject.fake;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeAdaptersContractTest {
    private static final Instant NOW = Instant.parse("2026-08-08T01:00:00Z");

    @Test
    void candleQueryHonorsIntervalCountAndClock() {
        FakeCandleQueryAdapter adapter = new FakeCandleQueryAdapter(
                Clock.fixed(NOW, ZoneOffset.UTC));

        var candles = adapter.getCandles("005930", CandleInterval.MINUTE_5, 3);

        assertEquals(3, candles.size());
        assertEquals(CandleInterval.MINUTE_5, candles.get(0).interval());
        assertEquals(NOW, candles.get(2).timestamp());
        assertThrows(IllegalArgumentException.class,
                () -> adapter.getCandles("005930", CandleInterval.DAY, 0));
    }

    @Test
    void marketStreamPublishesOnlyConnectedSubscriptions() {
        FakeMarketDataStreamAdapter adapter = new FakeMarketDataStreamAdapter();
        List<Quote> received = new ArrayList<>();
        List<ConnectionState> states = new ArrayList<>();
        adapter.addQuoteListener(received::add);
        adapter.addConnectionListener((state, detail) -> states.add(state));
        Quote quote = Quote.of("005930", new BigDecimal("73500"), 10, NOW);

        adapter.emit(quote);
        adapter.subscribe(List.of("005930"));
        adapter.connect();
        adapter.emit(quote);
        adapter.emit(Quote.of("000660", new BigDecimal("190500"), 20, NOW));

        assertEquals(List.of(quote), received);
        assertEquals(List.of(ConnectionState.CONNECTED), states);
        assertEquals(ConnectionState.CONNECTED, adapter.connectionState());
    }

    @Test
    void closingMarketStreamIsFinal() {
        FakeMarketDataStreamAdapter adapter = new FakeMarketDataStreamAdapter();
        adapter.connect();

        adapter.close();

        assertEquals(ConnectionState.DISCONNECTED, adapter.connectionState());
        assertThrows(IllegalStateException.class, adapter::connect);
    }
}
