package org.ossproject.finance.model.orderbook;

import java.math.BigDecimal;

/**
 * 누적 깊이 그래프 설정.
 *
 * @param rowCount              세로축이 담을 가격 단계 수
 * @param recenterMarginRows    기준가가 가장자리에서 이 단계 안까지 들어오면 세로축을 다시 잡는다
 * @param tickSize              호가 단위. {@code null} 이면 호가창에서 추론한다
 * @param depthHeadroom         가로축 기준을 관측 최대값의 몇 배로 잡을지(1.0 이상)
 * @param depthShrinkThreshold  곡선이 가로축의 이 비율보다 작아지면 축을 줄인다(0.0~1.0)
 */
public record DepthChartConfig(
        int rowCount,
        int recenterMarginRows,
        BigDecimal tickSize,
        double depthHeadroom,
        double depthShrinkThreshold
) {
    public DepthChartConfig {
        if (rowCount < 3) {
            throw new IllegalArgumentException("가격 단계 수는 3 이상이어야 합니다.");
        }
        if (recenterMarginRows < 0 || recenterMarginRows * 2 >= rowCount) {
            throw new IllegalArgumentException("재조정 여유가 화면 전체를 덮습니다.");
        }
        if (tickSize != null && tickSize.signum() <= 0) {
            throw new IllegalArgumentException("호가 단위는 0보다 커야 합니다.");
        }
        if (depthHeadroom < 1.0) {
            throw new IllegalArgumentException("가로축 여유는 1.0 이상이어야 합니다.");
        }
        if (depthShrinkThreshold < 0.0 || depthShrinkThreshold >= 1.0) {
            throw new IllegalArgumentException("축소 임계값은 0.0 이상 1.0 미만이어야 합니다.");
        }
    }

    /**
     * 기본값. 위아래 10단계씩, 가장자리 3단계에서 재조정, 가로축은 관측값의 1.3배,
     * 곡선이 축의 40% 아래로 줄면 축소.
     *
     * <p>축소 임계값을 낮게 둔 이유는, 조금만 줄어도 축을 조이면 곡선이 계속 커졌다
     * 작아졌다 하기 때문이다. 축이 바뀌는 일 자체가 사용자에게는 비용이다.
     */
    public static DepthChartConfig defaults() {
        return new DepthChartConfig(21, 3, null, 1.3, 0.4);
    }
}
