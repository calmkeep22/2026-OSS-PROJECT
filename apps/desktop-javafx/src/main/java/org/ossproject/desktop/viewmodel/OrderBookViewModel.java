package org.ossproject.desktop.viewmodel;

import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.PriceLadder;
import org.ossproject.finance.model.PriceLadderConfig;
import org.ossproject.finance.model.PriceLadderView;
import org.ossproject.finance.model.SecurityId;

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

    private PriceLadder ladder;
    private PriceLadderView view;
    private EventSubscription subscription;
    private SecurityId watching;

    public OrderBookViewModel(MarketApplicationPort market, Executor stateExecutor) {
        this(market, stateExecutor, PriceLadderConfig.defaults());
    }

    public OrderBookViewModel(MarketApplicationPort market, Executor stateExecutor,
                              PriceLadderConfig config) {
        this.market = Objects.requireNonNull(market, "market");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
        this.ladder = PriceLadder.create(Objects.requireNonNull(config, "config"));
    }

    /** 공급원이 실시간 호가를 주는지. 거짓이면 화면은 호가창 대신 안내를 보여 준다. */
    public boolean supported() {
        return market.supportsOrderBook();
    }

    /** 마지막으로 만들어진 화면. 아직 호가를 한 번도 받지 못했으면 비어 있다. */
    public Optional<PriceLadderView> currentView() {
        return Optional.ofNullable(view);
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
        Objects.requireNonNull(security, "security");
        Objects.requireNonNull(onUpdated, "onUpdated");
        stop();

        if (!security.equals(watching)) {
            ladder = PriceLadder.create(ladder.config());
            view = null;
        }
        watching = security;

        subscription = market.monitorOrderBook(security, book -> stateExecutor.execute(() -> {
            // 늦게 도착한 갱신이 이미 바뀐 종목의 화면을 건드리면 안 된다.
            if (!security.equals(watching)) {
                return;
            }
            apply(book);
            onUpdated.accept(view);
        }));
    }

    /** 구독을 해제한다. 여러 번 불러도 안전하다. */
    public void stop() {
        EventSubscription current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
    }

    private void apply(OrderBook book) {
        PriceLadder.Update update = ladder.update(book, null);
        ladder = update.ladder();
        view = update.view();
    }
}
