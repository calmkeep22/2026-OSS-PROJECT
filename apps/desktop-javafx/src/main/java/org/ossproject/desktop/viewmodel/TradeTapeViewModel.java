package org.ossproject.desktop.viewmodel;

import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.Trade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 종목 상세의 체결 목록.
 *
 * <p>활발한 종목은 초당 수십 건이 들어온다. 표가 계속 위로 밀리면 스크린리더로 읽던 자리를
 * 잃는다. 화면이 표에 초점이 있다고 알리면 갱신을 멈추고, 그동안 들어온 체결은 모아 둔다.
 * 초점이 떠나면 한 번에 반영하면서 몇 건이 밀렸는지 알린다.
 *
 * <p>목록 길이는 제한한다. 무한히 쌓으면 메모리와 화면이 모두 감당하지 못한다.
 */
public final class TradeTapeViewModel {

    /** 화면에 유지할 최대 건수. 넘으면 오래된 것부터 버린다. */
    public static final int DEFAULT_CAPACITY = 200;

    private final MarketApplicationPort market;
    private final Executor stateExecutor;
    private final int capacity;

    /** 최신이 앞에 온다. 체결 목록은 최근 것부터 읽는다. */
    private final Deque<Trade> visible = new ArrayDeque<>();
    /** 초점이 표에 있는 동안 들어온 체결. 초점이 떠나면 한 번에 반영한다. */
    private final Deque<Trade> held = new ArrayDeque<>();

    private EventSubscription subscription;
    private SecurityId watching;
    private boolean paused;
    private Consumer<List<Trade>> onUpdated = trades -> { };
    private Consumer<Integer> onHeldCountChanged = count -> { };

    public TradeTapeViewModel(MarketApplicationPort market, Executor stateExecutor) {
        this(market, stateExecutor, DEFAULT_CAPACITY);
    }

    public TradeTapeViewModel(MarketApplicationPort market, Executor stateExecutor, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("보관 건수는 1 이상이어야 합니다.");
        }
        this.market = Objects.requireNonNull(market, "market");
        this.stateExecutor = Objects.requireNonNull(stateExecutor, "stateExecutor");
        this.capacity = capacity;
    }

    /** 공급원이 체결 건별 정보를 주는지. 거짓이면 화면은 목록 대신 안내를 보여 준다. */
    public boolean supported() {
        return market.supportsTrades();
    }

    /** 지금 보여 줄 체결. 최신이 앞이다. */
    public List<Trade> trades() {
        return List.copyOf(visible);
    }

    /** 갱신을 멈춘 동안 밀려 있는 건수. */
    public int heldCount() {
        return held.size();
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * 구독을 시작한다.
     *
     * @param onUpdated          목록이 바뀌면 호출된다. 화면 스레드에서 온다
     * @param onHeldCountChanged 밀린 건수가 바뀌면 호출된다
     */
    public void start(SecurityId security, Consumer<List<Trade>> onUpdated,
                      Consumer<Integer> onHeldCountChanged) {
        Objects.requireNonNull(security, "security");
        this.onUpdated = Objects.requireNonNull(onUpdated, "onUpdated");
        this.onHeldCountChanged = Objects.requireNonNull(onHeldCountChanged, "onHeldCountChanged");
        stop();

        if (!security.equals(watching)) {
            visible.clear();
            held.clear();
        }
        watching = security;

        subscription = market.monitorTrades(security, trade -> stateExecutor.execute(() -> {
            // 늦게 도착한 체결이 이미 바뀐 종목의 목록에 섞이면 안 된다.
            if (!security.equals(watching)) {
                return;
            }
            accept(trade);
        }));
    }

    /**
     * 표에 초점이 있는 동안 갱신을 멈춘다.
     *
     * <p>읽는 중에 목록이 위로 밀리면 읽던 행이 사라진다. 멈춘 동안 들어온 체결은 버리지
     * 않고 모아 둔다.
     */
    public void setPaused(boolean value) {
        if (paused == value) {
            return;
        }
        paused = value;
        if (!paused && !held.isEmpty()) {
            flushHeld();
        }
    }

    public void stop() {
        EventSubscription current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
    }

    private void accept(Trade trade) {
        if (paused) {
            held.addFirst(trade);
            trim(held);
            onHeldCountChanged.accept(held.size());
            return;
        }
        visible.addFirst(trade);
        trim(visible);
        onUpdated.accept(trades());
    }

    /** 밀린 것을 순서대로 앞에 붙인다. 오래된 것이 먼저 들어가야 시간 순서가 유지된다. */
    private void flushHeld() {
        while (!held.isEmpty()) {
            visible.addFirst(held.removeLast());
        }
        trim(visible);
        onHeldCountChanged.accept(0);
        onUpdated.accept(trades());
    }

    private void trim(Deque<Trade> target) {
        while (target.size() > capacity) {
            target.removeLast();
        }
    }

    /** 화면이 목록을 처음 그릴 때 쓴다. */
    public List<Trade> snapshot() {
        return new ArrayList<>(visible);
    }
}
