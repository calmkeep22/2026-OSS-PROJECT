package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 가격 축이 고정된 호가창.
 *
 * <p>불변 객체다. {@link #update} 는 새 격자와 화면용 결과를 함께 돌려주므로,
 * 호출부는 돌려받은 격자를 다음 갱신에 다시 넘기면 된다.
 *
 * <pre>{@code
 * PriceLadder.Update update = ladder.update(orderBook, currentPrice);
 * ladder = update.ladder();
 * render(update.view());
 * }</pre>
 *
 * <p>호가 단위는 설정에 없으면 호가창의 가격 간격에서 추론한다. 거래소 호가 단위표는
 * 개정이 잦아서 코드에 넣어 두면 언젠가 격자가 통째로 어긋난다. 실제로 받은 가격의
 * 간격을 쓰면 그럴 일이 없다.
 */
public record PriceLadder(BigDecimal anchor, BigDecimal tickSize, PriceLadderConfig config) {

    /** 갱신 결과. 다음 갱신에 쓸 격자와 화면에 그릴 결과를 함께 담는다. */
    public record Update(PriceLadder ladder, PriceLadderView view) {
    }

    public PriceLadder {
        if (config == null) {
            throw new IllegalArgumentException("격자 설정은 필수입니다.");
        }
        if (anchor != null && anchor.signum() <= 0) {
            throw new IllegalArgumentException("기준 가격은 0보다 커야 합니다.");
        }
        if (tickSize != null && tickSize.signum() <= 0) {
            throw new IllegalArgumentException("호가 단위는 0보다 커야 합니다.");
        }
    }

    /** 아직 기준을 잡지 않은 격자. 첫 호가창을 받을 때 자동으로 자리를 잡는다. */
    public static PriceLadder create(PriceLadderConfig config) {
        return new PriceLadder(null, config == null ? null : config.tickSize(), config);
    }

    public boolean isInitialized() {
        return anchor != null && tickSize != null;
    }

    /**
     * 호가창을 반영해 새 격자와 화면 결과를 만든다.
     *
     * @param book           최신 호가창
     * @param referencePrice 격자 중심을 잡을 기준가(현재가). {@code null} 이면 호가 중간값을 쓴다
     */
    public Update update(OrderBook book, BigDecimal referencePrice) {
        if (book == null) {
            throw new IllegalArgumentException("호가창은 필수입니다.");
        }

        BigDecimal reference = resolveReference(book, referencePrice);
        BigDecimal tick = tickSize != null ? tickSize : inferTickSize(book).orElse(null);

        if (reference == null || tick == null) {
            // 기준을 잡을 정보가 아직 없다. 빈 화면을 돌려주되 격자는 그대로 둔다.
            return new Update(this, new PriceLadderView(
                    book.symbol(), List.of(), 0L, false, null, book.timestamp()));
        }

        boolean recenter = !isInitialized() || needsRecenter(reference, tick);
        BigDecimal newAnchor = recenter ? snap(reference, tick) : anchor;
        PriceLadder next = new PriceLadder(newAnchor, tick, config);

        String announcement = null;
        if (recenter && isInitialized()) {
            announcement = "호가 범위를 " + format(newAnchor) + "원 기준으로 옮겼습니다.";
        }

        return new Update(next, next.render(book, reference, recenter && isInitialized(), announcement));
    }

    /** 현재 격자가 담고 있는 가격들. 높은 가격이 앞에 온다. */
    public List<BigDecimal> prices() {
        if (!isInitialized()) {
            return List.of();
        }
        int half = config.rowCount() / 2;
        List<BigDecimal> prices = new ArrayList<>(config.rowCount());
        for (int offset = half; offset >= half - config.rowCount() + 1; offset--) {
            BigDecimal price = anchor.add(tickSize.multiply(BigDecimal.valueOf(offset)));
            if (price.signum() > 0) {
                prices.add(price);
            }
        }
        return List.copyOf(prices);
    }

    // ------------------------------------------------------------------
    // 내부
    // ------------------------------------------------------------------

    private PriceLadderView render(OrderBook book, BigDecimal reference,
                                   boolean recentered, String announcement) {
        Map<BigDecimal, long[]> byPrice = indexByPrice(book);
        long maxSize = book.maxSize();
        BigDecimal currentRowPrice = snap(reference, tickSize);

        List<PriceLadderRow> rows = new ArrayList<>(config.rowCount());
        for (BigDecimal price : prices()) {
            long[] sizes = byPrice.getOrDefault(normalize(price), new long[]{0L, 0L, 0L, 0L});
            rows.add(new PriceLadderRow(
                    price,
                    sizes[0], sizes[1], sizes[2], sizes[3],
                    barRatio(sizes[0], maxSize),
                    barRatio(sizes[1], maxSize),
                    normalize(price).compareTo(normalize(currentRowPrice)) == 0));
        }

        return new PriceLadderView(book.symbol(), rows, maxSize, recentered, announcement,
                book.timestamp());
    }

    /** 가격별로 {@code [매도잔량, 매수잔량, 매도증감, 매수증감]} 을 모은다. */
    private static Map<BigDecimal, long[]> indexByPrice(OrderBook book) {
        Map<BigDecimal, long[]> byPrice = new HashMap<>();
        for (OrderBookLevel level : book.levels()) {
            if (level.hasAsk()) {
                long[] slot = byPrice.computeIfAbsent(normalize(level.askPrice()),
                        key -> new long[]{0L, 0L, 0L, 0L});
                slot[0] += level.askSize();
                slot[2] += level.askDelta();
            }
            if (level.hasBid()) {
                long[] slot = byPrice.computeIfAbsent(normalize(level.bidPrice()),
                        key -> new long[]{0L, 0L, 0L, 0L});
                slot[1] += level.bidSize();
                slot[3] += level.bidDelta();
            }
        }
        return byPrice;
    }

    /**
     * 잔량이 있으면 최소 길이를 보장한다. 실 한 가닥짜리 막대는 저시력 사용자에게
     * "없음"과 구분되지 않기 때문이다.
     */
    private double barRatio(long size, long maxSize) {
        if (size <= 0L || maxSize <= 0L) {
            return 0.0;
        }
        double ratio = (double) size / (double) maxSize;
        return Math.min(1.0, Math.max(ratio, config.minBarRatio()));
    }

    /** 기준가가 격자 가장자리 여유 안까지 밀려왔는지 판단한다. */
    private boolean needsRecenter(BigDecimal reference, BigDecimal tick) {
        if (!isInitialized()) {
            return true;
        }
        if (tick.compareTo(tickSize) != 0) {
            // 호가 단위가 바뀌었다면 격자를 새로 잡아야 한다.
            return true;
        }
        int half = config.rowCount() / 2;
        int safeOffset = half - config.recenterMarginRows();
        BigDecimal upper = anchor.add(tick.multiply(BigDecimal.valueOf(safeOffset)));
        BigDecimal lower = anchor.subtract(tick.multiply(BigDecimal.valueOf(safeOffset)));
        return reference.compareTo(upper) > 0 || reference.compareTo(lower) < 0;
    }

    private static BigDecimal resolveReference(OrderBook book, BigDecimal referencePrice) {
        if (referencePrice != null && referencePrice.signum() > 0) {
            return referencePrice;
        }
        return book.midPrice().orElse(null);
    }

    /**
     * 호가창의 가격 간격에서 호가 단위를 추론한다.
     *
     * <p>매도끼리, 매수끼리 인접한 가격 차이 중 가장 작은 값을 쓴다. 일부 단계가 비어 있어도
     * 나머지에서 구할 수 있다.
     */
    static Optional<BigDecimal> inferTickSize(OrderBook book) {
        List<BigDecimal> asks = book.levels().stream()
                .filter(OrderBookLevel::hasAsk).map(OrderBookLevel::askPrice).sorted().toList();
        List<BigDecimal> bids = book.levels().stream()
                .filter(OrderBookLevel::hasBid).map(OrderBookLevel::bidPrice).sorted().toList();

        BigDecimal smallest = null;
        for (List<BigDecimal> side : List.of(asks, bids)) {
            for (int i = 1; i < side.size(); i++) {
                BigDecimal gap = side.get(i).subtract(side.get(i - 1)).abs();
                if (gap.signum() > 0 && (smallest == null || gap.compareTo(smallest) < 0)) {
                    smallest = gap;
                }
            }
        }
        return Optional.ofNullable(smallest);
    }

    /** 가격을 호가 단위의 배수로 맞춘다. */
    private static BigDecimal snap(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
    }

    /** 소수점 표기가 달라도 같은 가격이면 같은 키가 되도록 정규화한다. */
    private static BigDecimal normalize(BigDecimal price) {
        return price.stripTrailingZeros();
    }

    private static String format(BigDecimal value) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(value);
    }
}
