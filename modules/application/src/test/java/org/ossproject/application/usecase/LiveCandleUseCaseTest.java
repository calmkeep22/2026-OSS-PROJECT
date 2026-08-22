package org.ossproject.application.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.application.port.CandleListener;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.Quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCandleUseCaseTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 2026-08-20 09:30:00 KST */
    private static final Instant OPEN = Instant.parse("2026-08-20T00:30:00Z");
    private static final Clock CLOCK = Clock.fixed(OPEN.plusSeconds(20), SEOUL);

    /** 구독만 기록하고 시세를 직접 밀어 넣을 수 있는 가짜 스트림. */
    private static final class FakeStream implements MarketDataStreamPort {
        private final Set<String> subscriptions = new LinkedHashSet<>();
        private final List<QuoteListener> quoteListeners = new ArrayList<>();

        void push(Quote quote) {
            quoteListeners.forEach(listener -> listener.onQuote(quote));
        }

        @Override public void connect() { }
        @Override public void subscribe(Collection<String> symbols) { subscriptions.addAll(symbols); }
        @Override public void unsubscribe(Collection<String> symbols) { subscriptions.removeAll(symbols); }
        @Override public Set<String> subscriptions() { return Set.copyOf(subscriptions); }
        @Override public void addQuoteListener(QuoteListener listener) { quoteListeners.add(listener); }
        @Override public void removeQuoteListener(QuoteListener listener) { quoteListeners.remove(listener); }
        @Override public void addConnectionListener(ConnectionListener listener) { }
        @Override public void removeConnectionListener(ConnectionListener listener) { }
        @Override public ConnectionState connectionState() { return ConnectionState.CONNECTED; }
        @Override public void close() { }
    }

    private FakeStream stream;
    private List<Candle> history;
    private LiveCandleUseCase useCase;
    private List<Candle> updated;
    private List<Candle> completed;

    @BeforeEach
    void setUp() {
        stream = new FakeStream();
        history = new ArrayList<>();
        CandleQueryPort candles = (symbol, interval, count) -> List.copyOf(history);
        useCase = new LiveCandleUseCase(candles, stream, CandleInterval.MINUTE_1, CLOCK);

        updated = new ArrayList<>();
        completed = new ArrayList<>();
        useCase.addListener(new CandleListener() {
            @Override public void onCandleUpdated(Candle candle) { updated.add(candle); }
            @Override public void onCandleCompleted(Candle candle) { completed.add(candle); }
        });
    }

    private Quote tick(String price, long cumulativeVolume, long secondsAfterOpen) {
        return Quote.of("005930", new BigDecimal(price), cumulativeVolume,
                OPEN.plusSeconds(secondsAfterOpen));
    }

    @Test
    @DisplayName("시작하면 과거 봉을 돌려주고 구독을 건다")
    void startsSubscriptionAndReturnsHistory() {
        history.add(new Candle(OPEN.minusSeconds(60), CandleInterval.MINUTE_1,
                new BigDecimal("73000"), new BigDecimal("73200"),
                new BigDecimal("72900"), new BigDecimal("73100"), 5_000));

        List<Candle> loaded = useCase.start("005930", 100);

        assertEquals(1, loaded.size());
        assertEquals(Set.of("005930"), stream.subscriptions());
    }

    @Test
    @DisplayName("체결이 오면 마지막 봉을 갱신해 알린다")
    void updatesCandleOnTrade() {
        useCase.start("005930", 100);

        stream.push(tick("73500", 1_000, 10));
        stream.push(tick("74000", 1_200, 20));

        assertEquals(2, updated.size());
        Candle latest = updated.get(updated.size() - 1);
        assertEquals(0, new BigDecimal("74000").compareTo(latest.high()));
        assertEquals(200L, latest.volume());
    }

    @Test
    @DisplayName("분이 바뀌면 마감된 봉을 따로 알린다")
    void notifiesCompletedCandle() {
        useCase.start("005930", 100);
        stream.push(tick("73500", 1_000, 10));

        stream.push(tick("73900", 1_300, 70));

        assertEquals(1, completed.size());
        assertEquals(0, new BigDecimal("73500").compareTo(completed.get(0).close()));
        assertEquals(OPEN, completed.get(0).timestamp());
    }

    @Test
    @DisplayName("같은 시간대의 과거 마지막 봉을 이어받아 봉이 갈라지지 않는다")
    void continuesFromLastHistoricalCandle() {
        history.add(new Candle(OPEN, CandleInterval.MINUTE_1,
                new BigDecimal("73000"), new BigDecimal("73200"),
                new BigDecimal("72900"), new BigDecimal("73100"), 5_000));

        useCase.start("005930", 100);
        stream.push(tick("73500", 6_000, 30));

        Candle latest = updated.get(0);
        assertEquals(OPEN, latest.timestamp(), "같은 봉을 이어서 갱신");
        assertEquals(0, new BigDecimal("73000").compareTo(latest.open()), "과거 봉의 시가 유지");
        assertEquals(0, new BigDecimal("73500").compareTo(latest.high()), "고가는 갱신");
        assertTrue(completed.isEmpty(), "새 봉이 시작되면 안 된다");
    }

    @Test
    @DisplayName("멈추면 구독을 풀고 상태를 비운다")
    void stopsSubscription() {
        useCase.start("005930", 100);
        stream.push(tick("73500", 1_000, 10));

        useCase.stop("005930");

        assertTrue(stream.subscriptions().isEmpty());
        assertTrue(useCase.currentCandle("005930").isEmpty());
    }

    @Test
    @DisplayName("리스너가 예외를 던져도 다른 리스너는 계속 받는다")
    void isolatesFailingListener() {
        List<Candle> received = new ArrayList<>();
        useCase.addListener(candle -> {
            throw new IllegalStateException("화면 갱신 실패");
        });
        useCase.addListener(received::add);
        useCase.start("005930", 100);

        stream.push(tick("73500", 1_000, 10));

        assertEquals(1, received.size());
        assertFalse(updated.isEmpty());
    }
}
