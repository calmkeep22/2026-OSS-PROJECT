package org.ossproject.finance.model;

import java.math.BigDecimal;

/**
 * 누적 호가 깊이 곡선의 한 점.
 *
 * @param price          이 지점의 가격
 * @param levelSize      이 가격대의 잔량
 * @param cumulativeSize 최우선 호가부터 여기까지 더한 누적 잔량
 * @param wall           직전 지점 대비 누적 잔량이 크게 뛴 지점인지 여부
 */
public record DepthPoint(
        BigDecimal price,
        long levelSize,
        long cumulativeSize,
        boolean wall
) {
    public DepthPoint {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
        }
        if (levelSize < 0 || cumulativeSize < 0) {
            throw new IllegalArgumentException("잔량은 0 이상이어야 합니다.");
        }
    }
}
