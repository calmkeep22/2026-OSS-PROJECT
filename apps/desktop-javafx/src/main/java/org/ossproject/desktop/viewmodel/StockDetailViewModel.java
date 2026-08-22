package org.ossproject.desktop.viewmodel;

import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.PricePeriod;
import org.ossproject.finance.model.market.PricePoint;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.market.StockDetail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 선택 종목의 상세와 차트 데이터를 Application Port에서 비동기로 조회한다. */
public final class StockDetailViewModel {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    /** 차트 기간 버튼. 각 버튼은 봉 주기와 조회 개수로만 정의된다. */
    public enum ChartRange {
        MINUTE_1("1분", CandleInterval.MINUTE_1, 60),
        MINUTE_5("5분", CandleInterval.MINUTE_5, 78),
        MINUTE_15("15분", CandleInterval.MINUTE_15, 64),
        MINUTE_60("60분", CandleInterval.MINUTE_60, 48),
        DAY("일", CandleInterval.DAY, 30),
        WEEK("주", CandleInterval.WEEK, 26),
        MONTH("월", CandleInterval.MONTH, 24);

        private final String label;
        private final CandleInterval interval;
        private final int count;

        ChartRange(String label, CandleInterval interval, int count) {
            this.label = label;
            this.interval = interval;
            this.count = count;
        }

        public String label() { return label; }
        public CandleInterval interval() { return interval; }
        public int count() { return count; }
    }

    /** 상세 화면과 청각 차트가 함께 사용하는 하나의 조회 스냅샷. */
    public record InitialData(
            StockDetail detail,
            List<Candle> candles,
            List<PricePoint> chartPoints
    ) {
        public InitialData {
            Objects.requireNonNull(detail, "detail");
            candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
            chartPoints = List.copyOf(Objects.requireNonNull(chartPoints, "chartPoints"));
        }
    }

    private final DesktopSession session;
    private final MarketApplicationPort market;
    private final Executor stateExecutor;
    private final AtomicLong loadSequence = new AtomicLong();
    private final Map<ChartRange, List<PricePoint>> historyCache = new EnumMap<>(ChartRange.class);
    private final Map<ChartRange, List<Candle>> candleCache = new EnumMap<>(ChartRange.class);
    private EventSubscription candleSubscription;
    private SecurityId cachedSecurity;
    private StockDetail cachedDetail;
    private ChartRange selectedChartRange = ChartRange.DAY;

    public StockDetailViewModel(
            DesktopSession session,
            MarketApplicationPort market,
            Executor stateExecutor
    ) {
        this.session = Objects.requireNonNull(session, "session");
        this.market = Objects.requireNonNull(market, "market");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
    }

    public StockSelection selection() { return session.selectedStock(); }

    /** 상세 화면을 만들기 전에 상세와 기본 일 차트를 함께 준비한다. */
    public CompletionStage<InitialData> loadInitial() {
        SecurityId requested = selection().securityId();
        long requestId = loadSequence.incrementAndGet();
        CompletionStage<StockDetail> detail = market.loadDetail(requested);
        CompletionStage<List<Candle>> candles = queryCandles(requested, ChartRange.DAY);
        CompletableFuture<InitialData> result = new CompletableFuture<>();
        detail.thenCombine(candles, (loadedDetail, loadedCandles) ->
                new InitialData(loadedDetail, loadedCandles, toPricePoints(loadedCandles)))
                .whenComplete((data, failure) ->
                executeStateChange(result, () -> {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure));
                        return;
                    }
                    if (requestId != loadSequence.get()
                            || !requested.equals(selection().securityId())) {
                        result.completeExceptionally(
                                new IllegalStateException("선택 종목이 변경되어 이전 조회 결과를 버렸습니다."));
                        return;
                    }
                    replaceCache(requested, data.detail());
                    candleCache.put(ChartRange.DAY, data.candles());
                    historyCache.put(ChartRange.DAY, data.chartPoints());
                    selectedChartRange = ChartRange.DAY;
                    result.complete(data);
                }));
        return result;
    }

    /** 주문 화면처럼 상세만 필요한 흐름에서 사용한다. */
    public CompletionStage<StockDetail> loadDetail() {
        SecurityId requested = selection().securityId();
        long requestId = loadSequence.incrementAndGet();
        CompletableFuture<StockDetail> result = new CompletableFuture<>();
        market.loadDetail(requested).whenComplete((detail, failure) ->
                executeStateChange(result, () -> {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure));
                        return;
                    }
                    if (requestId != loadSequence.get()
                            || !requested.equals(selection().securityId())) {
                        result.completeExceptionally(
                                new IllegalStateException("선택 종목이 변경되어 이전 조회 결과를 버렸습니다."));
                        return;
                    }
                    replaceCache(requested, detail);
                    result.complete(detail);
                }));
        return result;
    }

    /** 캐시에 준비된 최신 상세. 네트워크 호출을 하지 않는다. */
    public StockDetail detail() {
        requireCurrentCache();
        return cachedDetail;
    }

    public boolean hasCurrentDetail() {
        return cachedDetail != null && selection().securityId().equals(cachedSecurity);
    }

    /** 현재 선택 종목에 대해 화면과 청각 차트가 공유할 봉 스냅샷이 준비됐는지 확인한다. */
    public boolean hasCurrentChartData() {
        return hasCurrentDetail() && candleCache.containsKey(selectedChartRange)
                && historyCache.containsKey(selectedChartRange);
    }

    public ChartRange selectedChartRange() {
        requireCurrentChartData();
        return selectedChartRange;
    }

    public List<Candle> selectedCandles() {
        requireCurrentChartData();
        return candleCache.get(selectedChartRange);
    }

    public CompletionStage<List<PricePoint>> loadHistory(ChartRange range) {
        Objects.requireNonNull(range, "range");
        SecurityId requested = selection().securityId();
        CompletableFuture<List<PricePoint>> result = new CompletableFuture<>();
        queryCandles(requested, range).whenComplete((candles, failure) ->
                executeStateChange(result, () -> {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure));
                        return;
                    }
                    if (!requested.equals(selection().securityId())) {
                        result.completeExceptionally(
                                new IllegalStateException("선택 종목이 변경되어 이전 차트 결과를 버렸습니다."));
                        return;
                    }
                    if (!requested.equals(cachedSecurity)) {
                        historyCache.clear();
                        candleCache.clear();
                        cachedDetail = null;
                    }
                    List<PricePoint> points = toPricePoints(candles);
                    cachedSecurity = requested;
                    candleCache.put(range, candles);
                    historyCache.put(range, points);
                    selectedChartRange = range;
                    result.complete(points);
                }));
        return result;
    }

    public List<PricePoint> history(ChartRange range) {
        requireCurrentCache();
        List<PricePoint> points = historyCache.get(range);
        if (points == null) throw new IllegalStateException("차트 데이터를 먼저 조회해야 합니다.");
        return points;
    }

    public CompletionStage<List<PricePoint>> loadHistory(PricePeriod period) {
        Objects.requireNonNull(period, "period");
        return switch (period) {
            case DAY -> queryHistory(selection().securityId(), CandleInterval.MINUTE_5, 78);
            case WEEK -> queryHistory(selection().securityId(), CandleInterval.DAY, 5);
            case MONTH -> queryHistory(selection().securityId(), CandleInterval.DAY, 22);
            case THREE_MONTHS -> queryHistory(selection().securityId(), CandleInterval.DAY, 66);
            case YEAR -> queryHistory(selection().securityId(), CandleInterval.WEEK, 52);
        };
    }

    private CompletionStage<List<Candle>> queryCandles(SecurityId security, ChartRange range) {
        return market.loadCandles(security, range.interval(), range.count())
                .thenApply(List::copyOf);
    }

    private CompletionStage<List<PricePoint>> queryHistory(
            SecurityId security, CandleInterval interval, int count
    ) {
        return market.loadCandles(security, interval, count).thenApply(this::toPricePoints);
    }

    private List<PricePoint> toPricePoints(List<Candle> candles) {
        return candles.stream()
                .map(candle -> candle.toPricePoint(MARKET_ZONE))
                .toList();
    }

    /** 선택 종목의 통화에 맞춘 금액 표기. */
    public String formatPrice(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        if (selection().overseas()) {
            return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return String.format("%,d원", value.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    /** 주문 화면 입력란에 넣을 단가. 표기 기호 없이 숫자만 돌려준다. */
    public String plainOrderPrice() {
        return detail().currentPrice().stripTrailingZeros().toPlainString();
    }

    private void replaceCache(SecurityId security, StockDetail detail) {
        if (!security.equals(cachedSecurity)) {
            historyCache.clear();
            candleCache.clear();
            selectedChartRange = ChartRange.DAY;
        }
        cachedSecurity = security;
        cachedDetail = Objects.requireNonNull(detail, "detail");
    }

    private void requireCurrentCache() {
        if (cachedDetail == null || !selection().securityId().equals(cachedSecurity)) {
            throw new IllegalStateException("선택 종목의 상세 정보를 먼저 조회해야 합니다.");
        }
    }

    private void requireCurrentChartData() {
        requireCurrentCache();
        if (!candleCache.containsKey(selectedChartRange)
                || !historyCache.containsKey(selectedChartRange)) {
            throw new IllegalStateException("선택 종목의 차트 데이터를 먼저 조회해야 합니다.");
        }
    }

    private Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /**
     * 선택한 기간의 마지막 봉을 실시간 체결로 갱신받는다.
     *
     * <p>거래소는 실시간 차트를 보내 주지 않으므로 체결을 모아 마지막 봉을 직접 갱신한다.
     * 이미 조회해 둔 봉을 함께 넘겨 같은 조회가 두 번 나가지 않게 하고, 마지막 봉이 둘로
     * 갈라지지 않게 한다.
     *
     * <p>이미 구독 중이면 먼저 해제한다. 기간이나 종목을 바꿀 때마다 새 구독이 쌓이면
     * 체결 한 건에 여러 번 다시 그리게 된다.
     *
     * @param onUpdated 갱신된 전체 지점. 화면 스레드에서 호출된다
     */
    public void startLiveChart(Consumer<List<PricePoint>> onUpdated) {
        Objects.requireNonNull(onUpdated, "onUpdated");
        requireCurrentChartData();
        stopLiveChart();

        SecurityId security = selection().securityId();
        ChartRange range = selectedChartRange;
        candleSubscription = market.monitorCandles(security, range.interval(),
                candleCache.get(range), candle -> stateExecutor.execute(() -> {
                    // 늦게 도착한 콜백이 이미 바뀐 종목이나 기간의 차트를 건드리면 안 된다.
                    if (!security.equals(selection().securityId()) || range != selectedChartRange) {
                        return;
                    }
                    applyLiveCandle(range, candle);
                    onUpdated.accept(historyCache.get(range));
                }));
    }

    /** 구독을 해제한다. 여러 번 불러도 안전하다. */
    public void stopLiveChart() {
        EventSubscription current = candleSubscription;
        candleSubscription = null;
        if (current != null) {
            current.close();
        }
    }

    /**
     * 진행 중인 봉으로 마지막 항목을 바꾼다. 새 봉이면 뒤에 붙인다.
     *
     * <p>시각 차트와 청각 차트가 같은 스냅샷을 보도록 두 캐시를 함께 고친다. 한쪽만 고치면
     * 같은 화면에서 두 표현이 다른 값을 말하게 된다.
     */
    private void applyLiveCandle(ChartRange range, Candle candle) {
        List<Candle> candles = candleCache.get(range);
        if (candles == null || candles.isEmpty()) {
            return;
        }
        List<Candle> updated = new ArrayList<>(candles);
        Candle last = updated.get(updated.size() - 1);
        if (last.timestamp().equals(candle.timestamp())) {
            updated.set(updated.size() - 1, candle);
        } else {
            updated.add(candle);
        }
        candleCache.put(range, List.copyOf(updated));
        historyCache.put(range, toPricePoints(updated));
    }

    private void executeStateChange(CompletableFuture<?> result, Runnable change) {
        try {
            stateExecutor.execute(() -> {
                try {
                    change.run();
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }
}
