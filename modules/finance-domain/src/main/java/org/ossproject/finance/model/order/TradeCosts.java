package org.ossproject.finance.model.order;

import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;

/**
 * 한 주문에 붙는 수수료와 세금.
 *
 * @param commission 매매수수료
 * @param tax        거래세. 매수면 0
 */
public record TradeCosts(BigDecimal commission, BigDecimal tax) {

    private static final TradeCosts UNKNOWN = new TradeCosts(null, null);

    public TradeCosts {
        if (commission != null && commission.signum() < 0) {
            throw new IllegalArgumentException("수수료는 0 이상이어야 합니다.");
        }
        if (tax != null && tax.signum() < 0) {
            throw new IllegalArgumentException("세금은 0 이상이어야 합니다.");
        }
    }

    /** 요율을 몰라 계산하지 못한 상태. */
    public static TradeCosts unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return commission != null && tax != null;
    }

    public BigDecimal total() {
        return isKnown() ? commission.add(tax) : BigDecimal.ZERO;
    }

    /**
     * 실제로 오갈 금액.
     *
     * <p>매수는 주문 금액에 비용을 더해 빠져나가고, 매도는 대금에서 비용을 빼고 들어온다.
     * 총액만 보여 주면 이 차이를 알 수 없다.
     */
    public BigDecimal settlementAmount(OrderSide side, BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        if (!isKnown() || side == null) {
            return amount;
        }
        return side == OrderSide.SELL ? amount.subtract(total()) : amount.add(total());
    }
}
