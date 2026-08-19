package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 누적 호가 깊이 곡선.
 *
 * <p>가격대별 잔량을 하나씩 비교하는 대신, 최우선 호가부터 차례로 더한 누적값을 본다.
 * 개별 막대를 읽으려면 정밀한 시각이 필요하지만, 누적 곡선이 그리는 <b>전체 윤곽</b>은
 * 시야가 흐려도 남는다. 저시력 사용자가 "어느 가격대에 물량이 몰려 있나"를 훑어보는 데
 * 유리한 이유다.
 *
 * <p>누적값은 단조 증가하므로 개별 잔량이 요동쳐도 곡선 모양은 비교적 안정적이다.
 * 화면이 덜 깜빡인다는 뜻이고, 이는 잔존 시력을 쓰는 사용자의 피로와 직결된다.
 *
 * <p><b>한계</b>: 누적이라 특정 가격의 잔량 자체는 읽을 수 없다. 정확한 값이 필요하면
 * {@link PriceLadder} 로 전환해서 봐야 한다. 두 뷰는 서로 다른 질문에 답한다.
 */
public record DepthCurve(
        String symbol,
        List<DepthPoint> askSide,
        List<DepthPoint> bidSide,
        BigDecimal midPrice,
        java.time.Instant timestamp
) {
    /**
     * 앞선 단계들의 평균 잔량 대비 이 배수 이상이면 "벽"으로 본다.
     *
     * <p>누적값으로 판단하면 안 된다. 누적은 단조 증가라 초반 몇 단계는 배수가 크게
     * 뛰는 것이 정상이어서, 평범한 호가창도 벽투성이가 된다.
     */
    private static final double WALL_RATIO = 3.0;

    public DepthCurve {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        askSide = List.copyOf(askSide == null ? List.of() : askSide);
        bidSide = List.copyOf(bidSide == null ? List.of() : bidSide);
    }

    /**
     * 호가창에서 누적 곡선을 만든다.
     *
     * <p>매도는 최우선(가장 싼 매도)에서 위로, 매수는 최우선(가장 비싼 매수)에서 아래로
     * 누적한다. 즉 양쪽 모두 현재가에서 멀어지는 방향으로 쌓인다.
     */
    public static DepthCurve from(OrderBook book) {
        if (book == null) {
            throw new IllegalArgumentException("호가창은 필수입니다.");
        }

        // 매도는 싼 값부터, 매수는 비싼 값부터. 양쪽 모두 현재가에서 멀어지는 순서다.
        List<DepthPoint> asks = accumulate(book.levels().stream()
                .filter(OrderBookLevel::hasAsk)
                .sorted(Comparator.comparing(OrderBookLevel::askPrice))
                .map(level -> new Slice(level.askPrice(), level.askSize()))
                .toList());

        List<DepthPoint> bids = accumulate(book.levels().stream()
                .filter(OrderBookLevel::hasBid)
                .sorted(Comparator.comparing(OrderBookLevel::bidPrice).reversed())
                .map(level -> new Slice(level.bidPrice(), level.bidSize()))
                .toList());

        return new DepthCurve(book.symbol(), asks, bids,
                book.midPrice().orElse(null), book.timestamp());
    }

    /** 누적 계산에 쓰는 한 단계의 가격과 잔량. */
    private record Slice(BigDecimal price, long size) {
    }

    private static List<DepthPoint> accumulate(List<Slice> slices) {
        List<DepthPoint> points = new ArrayList<>(slices.size());
        long cumulative = 0L;
        for (int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            // 앞선 단계들의 평균과 견준다. 첫 단계는 비교 대상이 없어 벽이 될 수 없다.
            boolean wall = i > 0 && slice.size() >= (double) cumulative / i * WALL_RATIO;
            cumulative += slice.size();
            points.add(new DepthPoint(slice.price(), slice.size(), cumulative, wall));
        }
        return points;
    }

    public boolean isEmpty() {
        return askSide.isEmpty() && bidSide.isEmpty();
    }

    /** 매도 쪽 총 누적 잔량. */
    public long totalAskDepth() {
        return askSide.isEmpty() ? 0L : askSide.get(askSide.size() - 1).cumulativeSize();
    }

    /** 매수 쪽 총 누적 잔량. */
    public long totalBidDepth() {
        return bidSide.isEmpty() ? 0L : bidSide.get(bidSide.size() - 1).cumulativeSize();
    }

    /** 가로축 정규화 기준. 양쪽 중 더 큰 누적값을 쓴다. */
    public long maxDepth() {
        return Math.max(totalAskDepth(), totalBidDepth());
    }

    /** 물량이 몰린 지점들. 음성으로 "어디에 벽이 있는지" 알릴 때 쓴다. */
    public List<DepthPoint> walls() {
        List<DepthPoint> walls = new ArrayList<>();
        askSide.stream().filter(DepthPoint::wall).forEach(walls::add);
        bidSide.stream().filter(DepthPoint::wall).forEach(walls::add);
        return List.copyOf(walls);
    }

    /**
     * 매수 쪽으로 기운 정도(%). 50이면 균형, 클수록 매수 우위.
     *
     * <p>곡선의 좌우 비대칭을 한 숫자로 요약한 값이다. 형태를 볼 수 없는 사용자에게
     * 그래프의 핵심을 한 마디로 전달할 수 있다.
     */
    public BigDecimal bidDominancePercent() {
        long total = totalAskDepth() + totalBidDepth();
        if (total == 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(totalBidDepth())
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    public Optional<BigDecimal> midPriceIfPresent() {
        return Optional.ofNullable(midPrice);
    }

    /** 음성으로 읽어 줄 수 있는 요약. */
    public String describe() {
        if (isEmpty()) {
            return "호가 정보가 없습니다.";
        }
        StringBuilder sb = new StringBuilder("매도 누적 ").append(totalAskDepth())
                .append("주, 매수 누적 ").append(totalBidDepth()).append("주. ")
                .append("매수 비중 ").append(bidDominancePercent()).append("퍼센트");
        List<DepthPoint> walls = walls();
        if (!walls.isEmpty()) {
            sb.append(". 물량이 몰린 가격은 ");
            for (int i = 0; i < walls.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(walls.get(i).price().toPlainString()).append("원");
            }
        }
        return sb.append('.').toString();
    }
}
