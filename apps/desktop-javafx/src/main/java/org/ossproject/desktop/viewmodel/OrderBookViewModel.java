package org.ossproject.desktop.viewmodel;

import org.ossproject.application.port.EventSubscription;
import org.ossproject.desktop.orderbook.WallAnnouncer;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketApplicationListener;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.DepthChart;
import org.ossproject.finance.model.DepthChartConfig;
import org.ossproject.finance.model.DepthChartView;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.PriceLadder;
import org.ossproject.finance.model.PriceLadderConfig;
import org.ossproject.finance.model.PriceLadderView;
import org.ossproject.finance.model.SecurityId;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 종목 상세의 호가창 상태.
 *
 * <p>가격 축을 고정한 사다리로 호가를 보여 준다. 보통 증권사 호가창은 가격 숫자가 계속
 * 바뀌는데, 화면을 확대해 보는 저시력 사용자에게는 "보던 자리" 가 매번 사라지는 셈이다.
 * 축을 옮겨야 할 때는 한 번에 크게 옮기고 그 사실을 문장으로 알린다.
 *
 * <p>갱신은 실시간 스트림 스레드에서 오므로 화면 스레드로 넘겨 처리한다.
 */
public final class OrderBookViewModel {

    private final MarketApplicationPort market;
    private final Executor stateExecutor;
    private final Clock clock;

    private PriceLadder ladder;
    private PriceLadderView view;
    private DepthChart depth;
    private final WallAnnouncer wallAnnouncer = new WallAnnouncer();
    private DepthChartView depthView;
    private EventSubscription subscription;
    private EventSubscription quoteSubscription;
    private SecurityId watching;
    /** 마지막 체결가. 격자 중심을 잡는 기준이다. 아직 못 받았으면 {@code null}. */
    private BigDecimal lastTradedPrice;
    /** 마지막으로 호가를 받은 시각. 실시간이 살아 있는지 판단하는 근거다. */
    private Instant lastOrderBookAt;

    public OrderBookViewModel(MarketApplicationPort market, Executor stateExecutor) {
        this(market, stateExecutor, PriceLadderConfig.defaults());
    }

    public OrderBookViewModel(MarketApplicationPort market, Executor stateExecutor,
                              PriceLadderConfig config) {
        this(market, stateExecutor, config, DepthChartConfig.defaults());
    }

    public OrderBookViewModel(MarketApplicationPort market, Executor stateExecutor,
                              PriceLadderConfig config, DepthChartConfig depthConfig) {
        this(market, stateExecutor, config, depthConfig, Clock.systemDefaultZone());
    }

    public OrderBookViewModel(MarketApplicationPort market, Executor stateExecutor,
                              PriceLadderConfig config, DepthChartConfig depthConfig, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.market = Objects.requireNonNull(market, "market");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
        this.ladder = PriceLadder.create(Objects.requireNonNull(config, "config"));
        this.depth = DepthChart.create(Objects.requireNonNull(depthConfig, "depthConfig"));
    }

    /** 공급원이 실시간 호가를 주는지. 거짓이면 화면은 호가창 대신 안내를 보여 준다. */
    public boolean supported() {
        return market.supportsOrderBook();
    }

    /** 마지막으로 만들어진 사다리. 아직 호가를 한 번도 받지 못했으면 비어 있다. */
    public Optional<PriceLadderView> currentView() {
        return Optional.ofNullable(view);
    }

    /** 마지막으로 만들어진 누적 깊이 그래프. */
    public Optional<DepthChartView> currentDepthView() {
        return Optional.ofNullable(depthView);
    }

    /**
     * 격자 중심이 실제 체결가인지 여부.
     *
     * <p>거짓이면 호가 중간값으로 잡은 것이다. 둘은 다른 값이므로 화면이 이름을 구분해
     * 표시해야 한다. 중간값을 "현재가" 라고 읽어 주면 사용자가 체결가로 오해한다.
     */
    public boolean centeredOnTradedPrice() {
        return lastTradedPrice != null;
    }

    /**
     * 실시간 갱신이 살아 있는지.
     *
     * <p>연결 상태만으로는 알 수 없다. 연결은 되어 있는데 데이터가 오지 않는 경우가 있다.
     * 장이 닫히면 그렇다. 마지막으로 호가를 받은 시각으로 판단한다.
     *
     * @param staleAfter 이 시간 동안 호가가 없으면 멈춘 것으로 본다
     */
    public boolean isLive(Duration staleAfter) {
        Objects.requireNonNull(staleAfter, "staleAfter");
        if (lastOrderBookAt == null) {
            return false;
        }
        return Duration.between(lastOrderBookAt, clock.instant()).compareTo(staleAfter) < 0;
    }

    /**
     * 호가 구독을 시작한다.
     *
     * <p>이미 구독 중이면 먼저 해제한다. 종목을 바꿀 때마다 구독이 쌓이면 호가 한 건에
     * 여러 번 다시 그리게 된다. 종목이 바뀌면 사다리도 새로 잡는다. 이전 종목의 가격 축을
     * 물려받으면 기준가가 격자 밖에 있어 첫 화면이 비어 보인다.
     *
     * @param onUpdated 갱신된 화면. 화면 스레드에서 호출된다
     */
    public void start(SecurityId security, Consumer<PriceLadderView> onUpdated) {
        start(security, onUpdated, depthView -> { });
    }

    /**
     * 사다리와 누적 깊이 그래프를 함께 받는다.
     *
     * <p>둘은 같은 호가창에서 나오므로 한 번에 갱신한다. 따로 구독하면 두 표현이 서로 다른
     * 시점의 값을 보여 줄 수 있다.
     */
    public void start(SecurityId security, Consumer<PriceLadderView> onUpdated,
                      Consumer<DepthChartView> onDepthUpdated) {
        start(security, onUpdated, onDepthUpdated, walls -> { });
    }

    /**
     * 물량이 몰린 곳 안내까지 함께 받는다.
     *
     * <p>안내는 벽 구성이 달라졌을 때만 온다. 갱신마다 읽으면 소음이 된다.
     */
    public void start(SecurityId security, Consumer<PriceLadderView> onUpdated,
                      Consumer<DepthChartView> onDepthUpdated,
                      Consumer<Optional<String>> onWalls) {
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(onUpdated, "onUpdated");
        Objects.requireNonNull(onDepthUpdated, "onDepthUpdated");
        Objects.requireNonNull(onWalls, "onWalls");
        stop();

        if (!security.equals(watching)) {
            ladder = PriceLadder.create(ladder.config());
            depth = DepthChart.create(depth.config());
            view = null;
            depthView = null;
            lastTradedPrice = null;
            lastOrderBookAt = null;
            wallAnnouncer.reset();
        }
        watching = security;

        // 격자 중심은 체결가로 잡는다. 호가 중간값은 스프레드가 벌어지면 체결가와 눈에
        // 띄게 달라지고, 체결이 나도 호가가 그대로면 움직이지 않는다.
        quoteSubscription = market.monitor(security, new MarketApplicationListener() {
            @Override public void onQuote(Quote quote) {
                stateExecutor.execute(() -> {
                    if (security.equals(watching) && quote.price() != null
                            && quote.price().signum() > 0) {
                        lastTradedPrice = quote.price();
                    }
                });
            }

            @Override public void onConnectionChanged(ConnectionState state, String detail) {
                // 연결 상태는 상태 표시줄이 따로 보여 준다.
            }
        });

        subscription = market.monitorOrderBook(security, book -> stateExecutor.execute(() -> {
            // 늦게 도착한 갱신이 이미 바뀐 종목의 화면을 건드리면 안 된다.
            if (!security.equals(watching)) {
                return;
            }
            apply(book);
            onUpdated.accept(view);
            onDepthUpdated.accept(depthView);
            onWalls.accept(wallAnnouncer.onOrderBook(book));
        }));
    }

    /** 구독을 해제한다. 여러 번 불러도 안전하다. */
    public void stop() {
        EventSubscription book = subscription;
        EventSubscription quotes = quoteSubscription;
        subscription = null;
        quoteSubscription = null;
        if (book != null) {
            book.close();
        }
        if (quotes != null) {
            quotes.close();
        }
    }

    private void apply(OrderBook book) {
        lastOrderBookAt = clock.instant();
        PriceLadder.Update update = ladder.update(book, lastTradedPrice);
        ladder = update.ladder();
        view = update.view();

        DepthChart.Update depthUpdate = depth.update(book, lastTradedPrice);
        depth = depthUpdate.chart();
        depthView = depthUpdate.view();
    }
}
