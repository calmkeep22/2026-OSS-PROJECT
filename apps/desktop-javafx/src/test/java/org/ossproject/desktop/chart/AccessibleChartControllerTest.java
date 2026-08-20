package org.ossproject.desktop.chart;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationListener;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Exchange;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.port.SonificationOutputListener;
import org.ossproject.sonification.port.SonificationOverflowPolicy;
import org.ossproject.sonification.port.SonificationPort;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessibleChartControllerTest {
    private static final SecurityId SECURITY = new SecurityId("005930", Exchange.KRX);
    private static final Instant START = Instant.parse("2026-08-17T00:00:00Z");

    @Test
    void mapsTheInjectedVisualCandleSnapshotWithoutRequerying() {
        CapturingMarket market = new CapturingMarket();
        RecordingSonificationPort audio = new RecordingSonificationPort();
        AccessibleChartController controller = controller(market, audio);

        assertEquals(SECURITY, controller.security());
        assertEquals(List.of(70_000.0, 71_000.0, 72_000.0),
                controller.samples().stream().map(sample -> sample.value()).toList());
        assertEquals(List.of(START, START.plus(Duration.ofDays(1)), START.plus(Duration.ofDays(2))),
                controller.samples().stream().map(sample -> sample.timestamp()).toList());
        assertEquals("일봉 3개 종가", controller.seriesDescription());
        assertEquals(0, market.candleQueries);

        controller.close();
    }

    @Test
    void liveQuotesDriveAudioAndStopReleasesTheOnlySubscription() {
        CapturingMarket market = new CapturingMarket();
        RecordingSonificationPort audio = new RecordingSonificationPort();
        AccessibleChartController controller = controller(market, audio);

        controller.startLive();
        controller.startLive();
        assertEquals(1, market.monitorCalls);

        Instant quoteTime = START.plus(Duration.ofDays(3));
        market.emit(Quote.of(SECURITY.symbol(), new BigDecimal("72500"), 10L, quoteTime));

        assertEquals(1, audio.frames.size());
        assertEquals(72_500.0, audio.frames.get(0).currentValue());
        assertEquals(quoteTime, audio.frames.get(0).timestamp());
        assertTrue(controller.liveStatusProperty().get().startsWith("재생 중"));

        controller.stopLive();
        market.emit(Quote.of(SECURITY.symbol(), new BigDecimal("73000"), 11L,
                quoteTime.plusSeconds(1)));

        assertEquals(1, market.subscriptionCloses);
        assertEquals(1, audio.frames.size());
        assertFalse(controller.isLiveRunning());
        controller.close();
    }

    @Test
    void staleAndOutOfOrderCallbacksCannotPlayAfterStop() {
        CapturingMarket market = new CapturingMarket();
        RecordingSonificationPort audio = new RecordingSonificationPort();
        AccessibleChartController controller = controller(market, audio);

        controller.startLive();
        MarketApplicationListener staleListener = market.listener;
        Instant latest = START.plus(Duration.ofDays(3));
        staleListener.onQuote(Quote.of(SECURITY.symbol(), new BigDecimal("72500"), 10L, latest));
        staleListener.onQuote(Quote.of(SECURITY.symbol(), new BigDecimal("71000"), 11L,
                latest.minusSeconds(1)));
        assertEquals(1, audio.frames.size());

        controller.stopLive();
        staleListener.onQuote(Quote.of(SECURITY.symbol(), new BigDecimal("73000"), 12L,
                latest.plusSeconds(1)));

        assertEquals(1, audio.frames.size());
        controller.close();
    }

    @Test
    void connectionStateIsExposedAndControllerBorrowsSharedPorts() {
        CapturingMarket market = new CapturingMarket();
        RecordingSonificationPort audio = new RecordingSonificationPort();
        AccessibleChartController controller = controller(market, audio);

        controller.startLive();
        market.listener.onConnectionChanged(ConnectionState.RECONNECTING, "네트워크 복구 중");

        assertTrue(controller.liveStatusProperty().get().contains("재연결 중"));
        assertTrue(controller.liveStatusProperty().get().contains("네트워크 복구 중"));

        assertDoesNotThrow(controller::close);
        assertDoesNotThrow(controller::close);
        assertEquals(1, market.subscriptionCloses);
        assertEquals(0, market.closeCalls);
        assertFalse(audio.closed);
    }

    private static AccessibleChartController controller(
            CapturingMarket market, RecordingSonificationPort audio
    ) {
        return new AccessibleChartController(
                SECURITY, stock(), candles(), "일봉 3개 종가", market, audio,
                (text, key) -> { }, ignored -> { }, Runnable::run);
    }

    private static StockDetail stock() {
        return new StockDetail(SECURITY.symbol(), "삼성전자", new BigDecimal("72000"),
                new BigDecimal("1000"), new BigDecimal("1.41"), PriceDirection.UP,
                new BigDecimal("71000"), new BigDecimal("72500"), new BigDecimal("70500"),
                1_000_000L, START.plus(Duration.ofDays(2)));
    }

    private static List<Candle> candles() {
        return List.of(
                candle(START, "69500", "70500", "69000", "70000"),
                candle(START.plus(Duration.ofDays(1)), "70000", "71500", "69800", "71000"),
                candle(START.plus(Duration.ofDays(2)), "71000", "72500", "70800", "72000"));
    }

    private static Candle candle(
            Instant timestamp, String open, String high, String low, String close
    ) {
        return new Candle(timestamp, CandleInterval.DAY, new BigDecimal(open),
                new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), 100L);
    }

    private static final class CapturingMarket implements MarketApplicationPort {
        private MarketApplicationListener listener;
        private int monitorCalls;
        private int subscriptionCloses;
        private int closeCalls;
        private int candleQueries;

        @Override public CompletionStage<List<SecuritySummary>> search(String query, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override public CompletionStage<StockDetail> loadDetail(SecurityId security) {
            return CompletableFuture.completedFuture(stock());
        }

        @Override public CompletionStage<List<Candle>> loadCandles(
                SecurityId security, CandleInterval interval, int count
        ) {
            candleQueries++;
            return CompletableFuture.completedFuture(candles());
        }

        @Override public EventSubscription monitorOrderBook(
                SecurityId security, org.ossproject.application.port.OrderBookListener listener) {
            return () -> { };
        }

        @Override public boolean supportsOrderBook() {
            return false;
        }

        @Override public EventSubscription monitorTrades(
                SecurityId security, org.ossproject.application.port.TradeListener listener) {
            return () -> { };
        }

        @Override public boolean supportsTrades() {
            return false;
        }

        @Override public EventSubscription observeConnection(
                org.ossproject.application.port.ConnectionListener listener) {
            return () -> { };
        }

        @Override public int liveSubscriptionCount() {
            return 0;
        }

        @Override public EventSubscription monitorCandles(
                SecurityId security, CandleInterval interval,
                List<Candle> history, org.ossproject.application.port.CandleListener listener
        ) {
            // 청각 차트는 봉이 아니라 체결 시세를 쓴다. 이 통로는 지나가지 않는다.
            return () -> { };
        }

        @Override public EventSubscription monitor(
                SecurityId security, MarketApplicationListener listener
        ) {
            assertEquals(SECURITY, security);
            this.listener = listener;
            monitorCalls++;
            return new EventSubscription() {
                private boolean closed;

                @Override public void close() {
                    if (closed) return;
                    closed = true;
                    subscriptionCloses++;
                }
            };
        }

        void emit(Quote quote) {
            if (listener != null) listener.onQuote(quote);
        }

        @Override public void close() { closeCalls++; }
    }

    private static final class RecordingSonificationPort implements SonificationPort {
        private final List<GraphAudioFrame> frames = new ArrayList<>();
        private boolean closed;
        private double volume;

        @Override public void play(GraphAudioFrame frame) {
            if (closed) throw new IllegalStateException("closed");
            frames.add(frame);
        }

        @Override public void stop() { }

        @Override public void setVolume(double volume) {
            if (closed) throw new IllegalStateException("closed");
            this.volume = volume;
        }

        @Override public SonificationOverflowPolicy overflowPolicy() {
            return SonificationOverflowPolicy.DROP_OLDEST;
        }

        @Override public void addOutputListener(SonificationOutputListener listener) { }
        @Override public void removeOutputListener(SonificationOutputListener listener) { }
        @Override public void close() { closed = true; }
    }
}
