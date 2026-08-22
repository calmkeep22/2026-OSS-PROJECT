package org.ossproject.finance.model.order;

import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 매매 수수료와 세금 요율.
 *
 * <p>주문 확인 창은 총액만 보여 주면 안 된다. 220,500원짜리 한 주를 사고 평가금액도
 * 220,500원인데 손익이 -1,980원으로 찍히는 일이 실제로 있었다. 수수료와 거래세다.
 * 주문 전에 알았다면 놀랄 일이 아니다.
 *
 * <p>요율은 증권사와 계좌 종류마다 다르다. 기본값을 두되 설정으로 덮어쓸 수 있게 한다.
 * 요율을 모르면 지어내지 않고 {@link #unknown()} 으로 두어 화면이 "확인되지 않았다" 고
 * 적게 한다.
 *
 * @param commissionRate 매매수수료율. 매수와 매도 모두에 붙는다
 * @param sellTaxRate    거래세율. <b>매도에만</b> 붙는다
 */
public record FeeSchedule(BigDecimal commissionRate, BigDecimal sellTaxRate) {

    private static final FeeSchedule UNKNOWN = new FeeSchedule(null, null);

    public FeeSchedule {
        if (commissionRate != null && commissionRate.signum() < 0) {
            throw new IllegalArgumentException("수수료율은 0 이상이어야 합니다.");
        }
        if (sellTaxRate != null && sellTaxRate.signum() < 0) {
            throw new IllegalArgumentException("거래세율은 0 이상이어야 합니다.");
        }
    }

    /**
     * 키움 모의투자 기본 요율. 수수료 0.35%, 거래세 0.20%.
     *
     * <p>실계좌 요율은 다르다. 설정에서 바꿀 수 있어야 한다.
     */
    public static FeeSchedule kiwoomMockDefaults() {
        return new FeeSchedule(new BigDecimal("0.0035"), new BigDecimal("0.0020"));
    }

    /** 요율을 모르는 상태. 계산하지 않고 모른다고 알린다. */
    public static FeeSchedule unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return commissionRate != null && sellTaxRate != null;
    }

    /**
     * 주문 금액에 붙는 비용을 계산한다.
     *
     * <p>원 단위로 버림한다. 증권사도 원 미만은 절사하므로 올림하면 실제보다 크게 나온다.
     *
     * @return 요율을 모르면 {@link TradeCosts#unknown()}
     */
    public TradeCosts costsFor(OrderSide side, BigDecimal amount) {
        if (!isKnown() || side == null || amount == null || amount.signum() <= 0) {
            return TradeCosts.unknown();
        }
        BigDecimal commission = amount.multiply(commissionRate).setScale(0, RoundingMode.DOWN);
        BigDecimal tax = side == OrderSide.SELL
                ? amount.multiply(sellTaxRate).setScale(0, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        return new TradeCosts(commission, tax);
    }
}
