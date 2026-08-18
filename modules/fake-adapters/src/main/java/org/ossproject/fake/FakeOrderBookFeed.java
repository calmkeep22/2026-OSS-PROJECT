package org.ossproject.fake;

import org.ossproject.application.port.OrderBookListener;
import org.ossproject.application.port.OrderBookQueryPort;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderBookLevel;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 실제 증권사 없이 호가창을 만들어 주는 가짜 피드.
 *
 * <p>앱 키 발급을 기다리지 않고 화면을 개발할 수 있게 하려는 것이다. 잔량 편차와 가격
 * 이동을 실제와 비슷하게 흉내 내므로, 막대 정규화와 격자 재조정이 실제로 동작하는지
 * 화면에서 바로 확인할 수 있다.
 *
 * <p>{@link #tick()} 을 호출할 때마다 한 장이 만들어진다. 화면 계층이 타이머로 부르면 되고,
 * 시드를 고정하면 항상 같은 순서로 재현되어 시연에 쓰기 좋다.
 */
public final class FakeOrderBookFeed implements OrderBookQueryPort {

    private static final int DEPTH = 10;

    private final String symbol;
    private final BigDecimal tickSize;
    private final Random random;
    private final Clock clock;
    private final List<OrderBookListener> listeners = new CopyOnWriteArrayList<>();

    private BigDecimal midPrice;
    private OrderBook latest;

    public FakeOrderBookFeed(String symbol, BigDecimal startPrice, BigDecimal tickSize, long seed) {
        this(symbol, startPrice, tickSize, seed, Clock.systemDefaultZone());
    }

    public FakeOrderBookFeed(String symbol, BigDecimal startPrice, BigDecimal tickSize,
                             long seed, Clock clock) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (startPrice == null || startPrice.signum() <= 0) {
            throw new IllegalArgumentException("시작 가격은 0보다 커야 합니다.");
        }
        if (tickSize == null || tickSize.signum() <= 0) {
            throw new IllegalArgumentException("호가 단위는 0보다 커야 합니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.symbol = symbol;
        this.midPrice = startPrice;
        this.tickSize = tickSize;
        this.random = new Random(seed);
        this.clock = clock;
        this.latest = build();
    }

    /** 삼성전자 시세를 흉내 낸 기본 피드. */
    public static FakeOrderBookFeed samsung() {
        return new FakeOrderBookFeed("005930", new BigDecimal("73500"), new BigDecimal("100"), 42L);
    }

    public void addListener(OrderBookListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(OrderBookListener listener) {
        listeners.remove(listener);
    }

    @Override
    public OrderBook getOrderBook(String requestedSymbol) {
        return latest;
    }

    /**
     * 다음 호가창을 만들어 리스너에게 알린다.
     *
     * <p>가격은 대체로 제자리에 머물다 가끔 한 호가씩 움직인다. 매 호출마다 크게 움직이면
     * 격자가 계속 재조정되어 고정 축의 장점을 확인할 수 없다.
     */
    public OrderBook tick() {
        int move = random.nextInt(10);
        if (move == 0) {
            midPrice = midPrice.add(tickSize);
        } else if (move == 1) {
            midPrice = midPrice.subtract(tickSize);
        }
        latest = build();
        for (OrderBookListener listener : listeners) {
            try {
                listener.onOrderBook(latest);
            } catch (RuntimeException ignored) {
                // 한 리스너의 실패가 다른 리스너를 막지 않는다.
            }
        }
        return latest;
    }

    private OrderBook build() {
        List<OrderBookLevel> levels = new ArrayList<>(DEPTH);
        for (int level = 1; level <= DEPTH; level++) {
            BigDecimal offset = tickSize.multiply(BigDecimal.valueOf(level));
            // 잔량 편차를 크게 두어 막대 정규화가 눈에 보이게 한다.
            long askSize = 50L + random.nextInt(2_000);
            long bidSize = 50L + random.nextInt(2_000);
            levels.add(new OrderBookLevel(level,
                    midPrice.add(offset), askSize, random.nextInt(200) - 100,
                    midPrice.subtract(offset), bidSize, random.nextInt(200) - 100));
        }
        return OrderBook.of(symbol, levels, clock.instant());
    }
}
