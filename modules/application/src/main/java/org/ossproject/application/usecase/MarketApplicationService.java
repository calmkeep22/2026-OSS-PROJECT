package org.ossproject.application.usecase;

import org.ossproject.application.port.CandleListener;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationListener;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.OrderBookListener;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.List;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 기존 조회·캔들·실시간 포트를 하나의 비동기 Application Port로 묶는다.
 *
 * <p>저수준 포트가 아직 종목코드 문자열을 사용하므로 어댑터 호출 직전까지만
 * {@link SecurityId}를 유지한다. KRX/NXT 동시 지원을 위해 저수준 포트도 SecurityId로
 * 전환되면 이 클래스의 문자열 변환만 제거하면 된다.
 */
public final class MarketApplicationService implements MarketApplicationPort {
    private record MonitorCount(SecurityId security, int count) {}

    private final StockQueryPort stockQuery;
    private final CandleQueryPort candleQuery;
    private final MarketDataStreamPort marketStream;
    private final Executor ioExecutor;
    private final Executor eventExecutor;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object monitorLock = new Object();
    private final Map<String, MonitorCount> monitorCounts = new HashMap<>();

    public MarketApplicationService(
            StockQueryPort stockQuery,
            CandleQueryPort candleQuery,
            MarketDataStreamPort marketStream,
            Executor ioExecutor
    ) {
        this(stockQuery, candleQuery, marketStream, ioExecutor, ioExecutor);
    }

    public MarketApplicationService(
            StockQueryPort stockQuery,
            CandleQueryPort candleQuery,
            MarketDataStreamPort marketStream,
            Executor ioExecutor,
            Executor eventExecutor
    ) {
        this(stockQuery, candleQuery, marketStream, ioExecutor, eventExecutor,
                Clock.systemDefaultZone());
    }

    /** 봉 경계 판정에 쓰는 시계를 지정한다. 테스트가 고정 시계를 넣는다. */
    public MarketApplicationService(
            StockQueryPort stockQuery,
            CandleQueryPort candleQuery,
            MarketDataStreamPort marketStream,
            Executor ioExecutor,
            Executor eventExecutor,
            Clock clock
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stockQuery = Objects.requireNonNull(stockQuery, "stockQuery");
        this.candleQuery = Objects.requireNonNull(candleQuery, "candleQuery");
        this.marketStream = Objects.requireNonNull(marketStream, "marketStream");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
    }

    @Override
    public CompletionStage<List<SecuritySummary>> search(String query, int limit) {
        if (closed.get()) return closedStage();
        if (limit <= 0) {
            return CompletableFuture.failedStage(
                    new IllegalArgumentException("검색 결과 개수는 1 이상이어야 합니다."));
        }
        String safeQuery = query == null ? "" : query.strip();
        return CompletableFuture.supplyAsync(
                () -> List.copyOf(stockQuery.search(safeQuery, limit)), ioExecutor);
    }

    @Override
    public CompletionStage<StockDetail> loadDetail(SecurityId security) {
        if (closed.get()) return closedStage();
        Objects.requireNonNull(security, "security");
        return CompletableFuture.supplyAsync(
                () -> stockQuery.getDetail(security), ioExecutor);
    }

    @Override
    public CompletionStage<List<Candle>> loadCandles(
            SecurityId security, CandleInterval interval, int count
    ) {
        if (closed.get()) return closedStage();
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(interval, "interval");
        if (count <= 0) {
            return CompletableFuture.failedStage(
                    new IllegalArgumentException("봉 조회 개수는 1 이상이어야 합니다."));
        }
        return CompletableFuture.supplyAsync(
                () -> List.copyOf(candleQuery.getCandles(security, interval, count)),
                ioExecutor);
    }

    @Override
    public EventSubscription monitor(SecurityId security, MarketApplicationListener listener) {
        if (closed.get()) throw new IllegalStateException("시장 Application 서비스가 종료되었습니다.");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(listener, "listener");

        QuoteListener quoteListener = quote -> {
            if (security.symbol().equalsIgnoreCase(quote.symbol())) {
                dispatch(() -> listener.onQuote(quote));
            }
        };
        ConnectionListener connectionListener = (state, detail) ->
                dispatch(() -> listener.onConnectionChanged(state, detail));

        marketStream.addQuoteListener(quoteListener);
        marketStream.addConnectionListener(connectionListener);
        boolean retained = false;
        try {
            retainMonitor(security);
            retained = true;
            if (marketStream.connectionState() == ConnectionState.DISCONNECTED
                    || marketStream.connectionState() == ConnectionState.FAILED) {
                marketStream.connect();
            }
        } catch (RuntimeException failure) {
            marketStream.removeQuoteListener(quoteListener);
            marketStream.removeConnectionListener(connectionListener);
            if (retained) releaseMonitor(security);
            throw failure;
        }

        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            marketStream.removeQuoteListener(quoteListener);
            marketStream.removeConnectionListener(connectionListener);
            releaseMonitor(security);
        };
    }

    @Override
    public EventSubscription monitorCandles(SecurityId security, CandleInterval interval,
                                            List<Candle> history, CandleListener listener) {
        if (closed.get()) throw new IllegalStateException("시장 Application 서비스가 종료되었습니다.");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(listener, "listener");

        LiveCandleUseCase live = new LiveCandleUseCase(candleQuery, marketStream, interval, clock);
        // 집계는 스트림 스레드에서 돈다. 화면으로 넘기는 것은 다른 이벤트와 같은 실행자를 쓴다.
        live.addListener(new CandleListener() {
            @Override public void onCandleUpdated(Candle candle) {
                dispatch(() -> listener.onCandleUpdated(candle));
            }

            @Override public void onCandleCompleted(Candle completed) {
                dispatch(() -> listener.onCandleCompleted(completed));
            }
        });

        boolean retained = false;
        try {
            live.startFrom(security.symbol(), history);
            retainMonitor(security);
            retained = true;
            if (marketStream.connectionState() == ConnectionState.DISCONNECTED
                    || marketStream.connectionState() == ConnectionState.FAILED) {
                marketStream.connect();
            }
        } catch (RuntimeException failure) {
            live.close();
            if (retained) releaseMonitor(security);
            throw failure;
        }

        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (!done.compareAndSet(false, true)) return;
            // 구독 해제는 releaseMonitor 에 맡긴다. 여기서 직접 풀면 같은 종목을 보고 있는
            // 다른 구독자의 시세까지 끊긴다. 집계 상태는 use case 와 함께 버려진다.
            live.close();
            releaseMonitor(security);
        };
    }

    @Override
    public EventSubscription observeConnection(ConnectionListener listener) {
        if (closed.get()) throw new IllegalStateException("시장 Application 서비스가 종료되었습니다.");
        Objects.requireNonNull(listener, "listener");

        ConnectionListener relay = (state, detail) -> dispatch(() -> listener.onConnectionStateChanged(state, detail));
        marketStream.addConnectionListener(relay);
        // 등록 직후 현재 상태를 한 번 알린다. 다음 변화까지 화면이 비어 있으면 안 된다.
        dispatch(() -> listener.onConnectionStateChanged(marketStream.connectionState(), null));

        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (!done.compareAndSet(false, true)) return;
            marketStream.removeConnectionListener(relay);
        };
    }

    @Override
    public EventSubscription monitorOrderBook(SecurityId security, OrderBookListener listener) {
        if (closed.get()) throw new IllegalStateException("시장 Application 서비스가 종료되었습니다.");
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(listener, "listener");

        OrderBookListener relay = book -> {
            if (security.symbol().equalsIgnoreCase(book.symbol())) {
                dispatch(() -> listener.onOrderBook(book));
            }
        };
        marketStream.addOrderBookListener(relay);

        boolean retained = false;
        try {
            retainMonitor(security);
            retained = true;
            if (marketStream.connectionState() == ConnectionState.DISCONNECTED
                    || marketStream.connectionState() == ConnectionState.FAILED) {
                marketStream.connect();
            }
        } catch (RuntimeException failure) {
            marketStream.removeOrderBookListener(relay);
            if (retained) releaseMonitor(security);
            throw failure;
        }

        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (!done.compareAndSet(false, true)) return;
            marketStream.removeOrderBookListener(relay);
            releaseMonitor(security);
        };
    }

    @Override
    public boolean supportsOrderBook() {
        return marketStream.supportsOrderBook();
    }

    @Override
    public int liveSubscriptionCount() {
        return marketStream.subscriptions().size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (monitorLock) {
            monitorCounts.clear();
        }
        marketStream.close();
    }

    private void retainMonitor(SecurityId security) {
        String symbol = security.symbol();
        synchronized (monitorLock) {
            MonitorCount current = monitorCounts.get(symbol);
            if (current != null && !current.security().equals(security)) {
                throw new IllegalStateException(
                        "현재 실시간 어댑터는 같은 종목코드의 거래소 동시 구독을 구분할 수 없습니다.");
            }
            if (current == null) {
                marketStream.subscribe(security);
                monitorCounts.put(symbol, new MonitorCount(security, 1));
            } else {
                monitorCounts.put(symbol, new MonitorCount(security, current.count() + 1));
            }
        }
    }

    private void releaseMonitor(SecurityId security) {
        String symbol = security.symbol();
        synchronized (monitorLock) {
            MonitorCount current = monitorCounts.get(symbol);
            if (current == null || !current.security().equals(security)) return;
            if (current.count() == 1) {
                marketStream.unsubscribe(security);
                monitorCounts.remove(symbol);
            } else {
                monitorCounts.put(symbol, new MonitorCount(security, current.count() - 1));
            }
        }
    }

    private void dispatch(Runnable callback) {
        try {
            eventExecutor.execute(() -> {
                try {
                    callback.run();
                } catch (RuntimeException ignored) {
                    // 한 화면 리스너의 실패가 WebSocket 수신 루프와 다른 구독을 중단하면 안 된다.
                }
            });
        } catch (RuntimeException ignored) {
            // 종료 중 executor가 작업을 거부해도 WebSocket 수신 루프까지 실패시키지 않는다.
        }
    }

    private <T> CompletionStage<T> closedStage() {
        return CompletableFuture.failedStage(
                new IllegalStateException("시장 Application 서비스가 종료되었습니다."));
    }
}
