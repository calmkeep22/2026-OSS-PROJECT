package org.ossproject.finance.model;

import java.math.BigDecimal;

/**
 * 고정 가격 격자 설정.
 *
 * <p>보통 증권사 호가창은 가격 숫자가 계속 바뀐다. 눈으로 훑는 사용자에게는 문제가 없지만,
 * 화면을 확대해서 보는 저시력 사용자에게는 "내가 보던 자리"가 매번 사라지는 셈이라
 * 읽기가 매우 어렵다.
 *
 * <p>그래서 가격 축을 고정하고 잔량만 제자리에서 갱신한다. 가격이 격자 밖으로 밀려날 때만
 * 축을 옮기되, 조금씩 미끄러지듯 옮기지 않고 한 번에 크게 옮긴 뒤 사용자에게 알린다.
 *
 * @param rowCount           격자 행 수. 홀수를 권장한다. 가운데 행이 기준가가 된다
 * @param recenterMarginRows 기준가가 위아래 가장자리에서 이 행 수 안까지 들어오면 축을 다시 잡는다
 * @param tickSize           호가 단위. {@code null} 이면 호가창의 가격 간격에서 추론한다
 * @param minBarRatio        잔량이 0보다 크면 최소한 이만큼은 막대를 그린다(0.0~1.0)
 */
public record PriceLadderConfig(
        int rowCount,
        int recenterMarginRows,
        BigDecimal tickSize,
        double minBarRatio
) {
    public PriceLadderConfig {
        if (rowCount < 3) {
            throw new IllegalArgumentException("격자 행 수는 3 이상이어야 합니다.");
        }
        if (recenterMarginRows < 0) {
            throw new IllegalArgumentException("재조정 여유 행 수는 0 이상이어야 합니다.");
        }
        if (recenterMarginRows * 2 >= rowCount) {
            throw new IllegalArgumentException(
                    "재조정 여유가 격자 전체를 덮습니다. 행 수를 늘리거나 여유를 줄여야 합니다.");
        }
        if (tickSize != null && tickSize.signum() <= 0) {
            throw new IllegalArgumentException("호가 단위는 0보다 커야 합니다.");
        }
        if (minBarRatio < 0.0 || minBarRatio > 1.0) {
            throw new IllegalArgumentException("최소 막대 비율은 0.0 이상 1.0 이하여야 합니다.");
        }
    }

    /**
     * 기본값. 21행(위아래 10단계 + 기준가), 가장자리 3행 안에 들어오면 재조정,
     * 잔량이 있으면 최소 8% 막대.
     *
     * <p>최소 막대 비율을 두는 이유는, 잔량 편차가 커서 작은 값이 실 한 가닥으로 그려지면
     * 저시력 사용자에게는 "없는 것"과 구분되지 않기 때문이다.
     */
    public static PriceLadderConfig defaults() {
        return new PriceLadderConfig(21, 3, null, 0.08);
    }

    /** 화면이 좁을 때 쓰는 축소 격자. */
    public static PriceLadderConfig compact() {
        return new PriceLadderConfig(11, 2, null, 0.10);
    }
}
