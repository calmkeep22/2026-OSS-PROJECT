package org.ossproject.finance.model;

import java.math.BigDecimal;

/**
 * 증권사가 계산해 내려준 평가 값.
 *
 * <p>실제 증권사 프로그램은 평가금액과 손익을 앱에서 다시 계산하지 않고 서버가 준 값을
 * 그대로 보여 준다. 매매수수료, 거래세, 미수 이자처럼 우리가 세지 않는 항목이 섞여 있어서
 * 직접 계산하면 증권사 화면과 어긋난다. 실제로 22만원짜리 한 주를 사면 우리 계산은
 * 손익을 -500원으로 보지만 수수료를 포함한 증권사 값은 -2,480원이다.
 *
 * <p>값을 받지 못하면 {@link #none()} 을 쓴다. 이때는 각 모델이 직접 계산한 값으로
 * 물러선다. 어느 쪽 값인지는 화면에서 밝힌다.
 *
 * <p>구성 요소는 모두 {@code null} 일 수 있다. 증권사가 일부만 주는 경우가 있어서,
 * 받은 것만 쓰고 나머지는 직접 계산한다.
 *
 * @param purchaseAmount 매입금액
 * @param evaluation     평가금액
 * @param profitLoss     평가손익. 손실이면 음수
 * @param profitLossRate 수익률(%)
 */
public record ReportedValuation(
        BigDecimal purchaseAmount,
        BigDecimal evaluation,
        BigDecimal profitLoss,
        BigDecimal profitLossRate
) {

    private static final ReportedValuation NONE =
            new ReportedValuation(null, null, null, null);

    /** 증권사가 평가 값을 주지 않은 경우. */
    public static ReportedValuation none() {
        return NONE;
    }

    /** 하나라도 받은 값이 있는지. 화면이 출처를 밝힐 때 쓴다. */
    public boolean isPresent() {
        return purchaseAmount != null || evaluation != null
                || profitLoss != null || profitLossRate != null;
    }

    /** 받은 값이 있으면 그것을, 없으면 직접 계산한 값을 돌려준다. */
    BigDecimal or(BigDecimal reported, BigDecimal computed) {
        return reported != null ? reported : computed;
    }
}
