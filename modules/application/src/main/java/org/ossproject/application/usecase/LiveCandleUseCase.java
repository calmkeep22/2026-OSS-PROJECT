package org.ossproject.application.usecase;

import org.ossproject.application.port.CandleListener;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleAggregator;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Quote;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 과거 봉과 실시간 갱신을 하나로 묶는다.
 *
 * <p>거래소는 "실시간 차트"를 보내 주지 않는다. 과거 봉은 조회로 한 번 받고, 그 뒤
 * 마지막 봉은 실시간 체결을 모아 직접 갱신해야 한다. 화면이 이 두 가지를 따로 신경 쓰지
 * 않도록 여기서 이어 붙인다.
 *
 * <pre>{@code
 * List<Candle> history = useCase.start("005930");   // 과거 봉 + 구독 시작
 * useCase.addListener(candle -> 화면의 마지막 봉 갱신);
 * }</pre>
 *
 * <p>리스너는 실시간 스트림 스레드에서 호출된다. 화면 계층은 반드시 자기 스레드로
 * 넘겨서 처리해야 한다.
 */
public final class LiveCandleUseCase implements QuoteListener {

    private final CandleQueryPort candles;
    private final MarketDataStreamPort stream;
    private final CandleAggregator aggregator;
    private final Clock clock;
    private final List<CandleListener> listeners = new CopyOnWriteArrayList<>();

    public LiveCandleUseCase(CandleQueryPort candles, MarketDataStreamPort stream,
                             CandleInterval interval, Clock clock) {
        if (candles == null) {
            throw new IllegalArgumentException("봉 조회 포트는 필수입니다.");
        }
        if (stream == null) {
            throw new IllegalArgumentException("실시간 스트림은 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.candles = candles;
        this.stream = stream;
        this.aggregator = new CandleAggregator(interval);
        this.clock = clock;
        stream.addQuoteListener(this);
    }

    public CandleInterval interval() {
        return aggregator.interval();
    }

    /**
     * 과거 봉을 받아 오고 실시간 구독을 시작한다.
     *
     * <p>마지막 봉을 집계기에 이어 붙인다. 이 과정을 빠뜨리면 같은 시간대의 봉이 둘로
     * 갈라져 화면에서 마지막 봉이 두 번 그려진다.
     *
     * @return 오래된 것부터 시간순 과거 봉
     */
    public List<Candle> start(String symbol, int count) {
        requireSymbol(symbol);
        List<Candle> history = candles.getCandles(symbol, aggregator.interval(), count);
        if (!history.isEmpty()) {
            aggregator.prime(symbol, history.get(history.size() - 1), clock.instant());
        }
        stream.subscribe(List.of(symbol));
        return history;
    }

    /** 구독을 멈추고 쌓아 둔 상태를 비운다. */
    public void stop(String symbol) {
        requireSymbol(symbol);
        stream.unsubscribe(List.of(symbol));
        aggregator.forget(symbol);
    }

    /** 지금 진행 중인 봉. */
    public Optional<Candle> currentCandle(String symbol) {
        return aggregator.current(symbol);
    }

    public void addListener(CandleListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(CandleListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onQuote(Quote quote) {
        aggregator.onQuote(quote).ifPresent(result -> {
            result.completed().ifPresent(this::notifyCompleted);
            notifyUpdated(result.candle());
        });
    }

    /** 한 리스너가 실패해도 나머지는 계속 받아야 한다. */
    private void notifyUpdated(Candle candle) {
        for (CandleListener listener : listeners) {
            try {
                listener.onCandleUpdated(candle);
            } catch (RuntimeException ignored) {
                // 화면 갱신 실패가 다음 체결 처리를 막지 않도록 삼킨다.
            }
        }
    }

    private void notifyCompleted(Candle candle) {
        for (CandleListener listener : listeners) {
            try {
                listener.onCandleCompleted(candle);
            } catch (RuntimeException ignored) {
                // 위와 같다.
            }
        }
    }

    private static void requireSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
    }
}
