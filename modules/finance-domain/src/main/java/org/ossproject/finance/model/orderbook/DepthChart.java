package org.ossproject.finance.model.orderbook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 두 축이 모두 고정된 누적 깊이 그래프.
 *
 * <p>세로축(가격) 고정만으로는 부족하다. 총잔량이 바뀔 때마다 가로축 배율이 달라지면
 * 곡선 모양이 데이터와 무관하게 출렁이고, 그러면 세로축을 고정한 이점이 절반은 사라진다.
 * 그래서 <b>가로축에도 같은 원리</b>를 적용한다. 평소에는 고정해 두고, 곡선이 화면 밖으로
 * 나가거나 너무 작아졌을 때만 한 번에 조정하고 사용자에게 알린다.
 *
 * <p>{@link PriceLadder} 와 같은 사용 방식이다. 불변 객체이며 {@link #update} 가
 * 다음에 쓸 그래프와 화면용 결과를 함께 돌려준다.
 */
public record DepthChart(
        BigDecimal priceAnchor,
        BigDecimal tickSize,
        long depthScale,
        DepthChartConfig config
) {
    /** 갱신 결과. */
    public record Update(DepthChart chart, DepthChartView view) {
    }

    public DepthChart {
        if (config == null) {
            throw new IllegalArgumentException("그래프 설정은 필수입니다.");
        }
        if (depthScale < 0) {
            throw new IllegalArgumentException("깊이 기준은 0 이상이어야 합니다.");
        }
    }

    public static DepthChart create(DepthChartConfig config) {
        return new DepthChart(null, config == null ? null : config.tickSize(), 0L, config);
    }

    public boolean isInitialized() {
        return priceAnchor != null && tickSize != null && depthScale > 0L;
    }

    /** 세로축이 담는 가격 범위의 절반 폭. */
    private BigDecimal halfSpan(BigDecimal tick) {
        return tick.multiply(BigDecimal.valueOf(config.rowCount() / 2));
    }

    /**
     * 호가창을 반영해 새 그래프와 화면 결과를 만든다.
     *
     * @param referencePrice 세로축 중심을 잡을 기준가. {@code null} 이면 호가 중간값을 쓴다
     */
    public Update update(OrderBook book, BigDecimal referencePrice) {
        if (book == null) {
            throw new IllegalArgumentException("호가창은 필수입니다.");
        }
        DepthCurve curve = DepthCurve.from(book);

        BigDecimal reference = referencePrice != null && referencePrice.signum() > 0
                ? referencePrice
                : curve.midPrice();
        BigDecimal tick = tickSize != null ? tickSize : PriceLadder.inferTickSize(book).orElse(null);

        if (reference == null || tick == null || curve.isEmpty()) {
            return new Update(this, emptyView(book));
        }

        boolean recenterPrice = !isInitialized() || needsPriceRecenter(reference, tick);
        boolean rescaleDepth = needsDepthRescale(curve.maxDepth());

        BigDecimal newAnchor = recenterPrice ? snap(reference, tick) : priceAnchor;
        long newDepthScale = rescaleDepth ? chooseDepthScale(curve.maxDepth()) : depthScale;
        DepthChart next = new DepthChart(newAnchor, tick, newDepthScale, config);

        boolean announceable = isInitialized();
        String announcement = null;
        if (announceable && (recenterPrice || rescaleDepth)) {
            announcement = buildAnnouncement(recenterPrice, rescaleDepth, newAnchor, newDepthScale);
        }

        return new Update(next, next.render(curve, book.symbol(),
                announceable && (recenterPrice || rescaleDepth), announcement));
    }

    // ------------------------------------------------------------------
    // 내부
    // ------------------------------------------------------------------

    private DepthChartView render(DepthCurve curve, String symbol,
                                  boolean recentered, String announcement) {
        BigDecimal span = halfSpan(tickSize);
        BigDecimal top = priceAnchor.add(span);
        BigDecimal bottom = priceAnchor.subtract(span);

        List<DepthChartView.Plot> asks = plot(curve.askSide(), top, bottom);
        List<DepthChartView.Plot> bids = plot(curve.bidSide(), top, bottom);

        return new DepthChartView(symbol, asks, bids, top, bottom, curve.midPrice(),
                depthScale, recentered, announcement, curve.timestamp());
    }

    /** 화면 범위를 벗어난 점은 버린다. 잘린 곡선을 억지로 그리면 형태가 왜곡된다. */
    private List<DepthChartView.Plot> plot(List<DepthPoint> points, BigDecimal top, BigDecimal bottom) {
        BigDecimal range = top.subtract(bottom);
        if (range.signum() <= 0) {
            return List.of();
        }
        List<DepthChartView.Plot> plots = new ArrayList<>(points.size());
        for (DepthPoint point : points) {
            if (point.price().compareTo(top) > 0 || point.price().compareTo(bottom) < 0) {
                continue;
            }
            double priceRatio = point.price().subtract(bottom)
                    .divide(range, 6, RoundingMode.HALF_UP).doubleValue();
            double depthRatio = depthScale <= 0L
                    ? 0.0
                    : Math.min(1.0, (double) point.cumulativeSize() / (double) depthScale);
            plots.add(new DepthChartView.Plot(point.price(), point.cumulativeSize(),
                    clamp(priceRatio), depthRatio, point.wall()));
        }
        return plots;
    }

    private boolean needsPriceRecenter(BigDecimal reference, BigDecimal tick) {
        if (priceAnchor == null || tickSize == null || tick.compareTo(tickSize) != 0) {
            return true;
        }
        int safeOffset = config.rowCount() / 2 - config.recenterMarginRows();
        BigDecimal margin = tick.multiply(BigDecimal.valueOf(safeOffset));
        return reference.compareTo(priceAnchor.add(margin)) > 0
                || reference.compareTo(priceAnchor.subtract(margin)) < 0;
    }

    /**
     * 가로축을 다시 잡아야 하는지 판단한다.
     *
     * <p>곡선이 화면을 넘치거나, 반대로 너무 작아져 형태를 알아볼 수 없을 때만 조정한다.
     * 매 갱신마다 최대값에 맞추면 곡선이 계속 늘었다 줄었다 해서 읽을 수 없다.
     */
    private boolean needsDepthRescale(long observedMax) {
        if (depthScale <= 0L) {
            return observedMax > 0L;
        }
        if (observedMax > depthScale) {
            return true;
        }
        return observedMax < depthScale * config.depthShrinkThreshold();
    }

    /**
     * 가로축 기준을 고른다.
     *
     * <p>관측값에 여유를 붙인 뒤 눈에 익은 단위로 올림한다. 기준이 1,237 처럼 어중간하면
     * 축이 바뀔 때마다 곡선이 미세하게 달라 보여, 바뀌었는지조차 알기 어렵다.
     */
    private long chooseDepthScale(long observedMax) {
        long padded = (long) Math.ceil(observedMax * config.depthHeadroom());
        if (padded <= 0L) {
            return 0L;
        }
        long unit = (long) Math.pow(10, Math.max(0, (int) Math.log10(padded) - 1));
        return ((padded + unit - 1) / unit) * unit;
    }

    private String buildAnnouncement(boolean recenterPrice, boolean rescaleDepth,
                                     BigDecimal newAnchor, long newDepthScale) {
        if (recenterPrice && rescaleDepth) {
            return "가격 범위를 " + format(newAnchor) + "원 기준으로, 잔량 기준을 "
                    + format(BigDecimal.valueOf(newDepthScale)) + "주로 옮겼습니다.";
        }
        if (recenterPrice) {
            return "가격 범위를 " + format(newAnchor) + "원 기준으로 옮겼습니다.";
        }
        return "잔량 기준을 " + format(BigDecimal.valueOf(newDepthScale)) + "주로 바꿨습니다.";
    }

    private DepthChartView emptyView(OrderBook book) {
        return new DepthChartView(book.symbol(), List.of(), List.of(), null, null, null,
                depthScale, false, null, book.timestamp());
    }

    private static double clamp(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }

    private static BigDecimal snap(BigDecimal price, BigDecimal tick) {
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick);
    }

    private static String format(BigDecimal value) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(value);
    }
}
