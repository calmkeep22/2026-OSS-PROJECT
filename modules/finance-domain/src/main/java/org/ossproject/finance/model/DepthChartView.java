package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 화면에 그릴 준비가 끝난 누적 깊이 그래프.
 *
 * <p>두 축이 모두 정규화되어 있다. 화면 계층은 비율에 폭·높이를 곱하기만 하면 되고,
 * 축 고정이나 재조정 규칙을 알 필요가 없다.
 *
 * @param askPoints    매도 쪽. 현재가에서 위로 멀어지는 순서
 * @param bidPoints    매수 쪽. 현재가에서 아래로 멀어지는 순서
 * @param depthScale   가로축 정규화 기준이 된 누적 잔량
 * @param recentered   이번 갱신에서 축이 옮겨졌는지 여부
 * @param announcement 축이 옮겨졌을 때 읽어 줄 문장. 아니면 {@code null}
 */
public record DepthChartView(
        String symbol,
        List<Plot> askPoints,
        List<Plot> bidPoints,
        BigDecimal highestPrice,
        BigDecimal lowestPrice,
        BigDecimal midPrice,
        long depthScale,
        boolean recentered,
        String announcement,
        Instant timestamp
) {
    /**
     * 그래프의 한 점. 좌표는 0.0~1.0 으로 정규화되어 있다.
     *
     * @param priceRatio 세로 위치. 0.0 이 화면 아래(저가), 1.0 이 위(고가)
     * @param depthRatio 가로 길이. 누적 잔량 비율
     * @param wall       물량이 몰린 지점인지 여부
     */
    public record Plot(
            BigDecimal price,
            long cumulativeSize,
            double priceRatio,
            double depthRatio,
            boolean wall
    ) {
        public Plot {
            if (priceRatio < 0.0 || priceRatio > 1.0 || depthRatio < 0.0 || depthRatio > 1.0) {
                throw new IllegalArgumentException("좌표 비율은 0.0 이상 1.0 이하여야 합니다.");
            }
        }
    }

    public DepthChartView {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        askPoints = List.copyOf(askPoints == null ? List.of() : askPoints);
        bidPoints = List.copyOf(bidPoints == null ? List.of() : bidPoints);
        if (depthScale < 0) {
            throw new IllegalArgumentException("깊이 기준은 0 이상이어야 합니다.");
        }
    }

    public boolean isEmpty() {
        return askPoints.isEmpty() && bidPoints.isEmpty();
    }

    public Optional<String> announcementIfPresent() {
        return Optional.ofNullable(announcement);
    }

    public Optional<BigDecimal> midPriceIfPresent() {
        return Optional.ofNullable(midPrice);
    }
}
