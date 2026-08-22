package org.ossproject.finance.model.account;

import java.math.BigDecimal;

/**
 * 증권사가 내려주는 예수금 단계.
 *
 * <p>실제 증권사 화면은 예수금을 하나로 보여 주지 않는다. "지금 얼마나 주문할 수 있나",
 * "지금 얼마나 뽑을 수 있나", "결제가 끝나면 얼마가 남나" 는 서로 다른 질문이고 답도 다르다.
 * 하나로 뭉치면 사용자가 어느 질문의 답을 보고 있는지 알 수 없다.
 *
 * <p>국내 주식 대금은 D+2 에 결제된다. 그래서 매수 당일 {@link #cash()} 는 줄지 않고
 * {@link #settledCash()} 만 줄어든다. 총자산을 계산할 때 {@link #cash()} 를 쓰면 이미 사 둔
 * 종목과 그 대금이 양쪽에서 각각 세어져 매수 금액만큼 부풀어 오른다.
 *
 * @param cash         예수금(D+0). 오늘 계좌에 실제로 있는 현금
 * @param settledCash  D+2 추정예수금. 결제가 끝난 뒤 남을 현금.
 *                     증거금 매수로 미수가 나면 <b>음수</b>가 된다
 * @param orderable    주문가능금액
 * @param withdrawable 출금가능금액
 */
public record Deposits(
        BigDecimal cash,
        BigDecimal settledCash,
        BigDecimal orderable,
        BigDecimal withdrawable
) {

    public Deposits {
        if (cash == null || cash.signum() < 0) {
            throw new IllegalArgumentException("예수금은 0 이상이어야 합니다.");
        }
        if (settledCash == null) {
            throw new IllegalArgumentException("D+2 추정예수금은 필수입니다.");
        }
        if (orderable == null || orderable.signum() < 0) {
            throw new IllegalArgumentException("주문가능금액은 0 이상이어야 합니다.");
        }
        if (withdrawable == null || withdrawable.signum() < 0) {
            throw new IllegalArgumentException("출금가능금액은 0 이상이어야 합니다.");
        }
    }

    /**
     * 모의 원장처럼 단계 구분이 없는 경우. 네 값을 모두 같게 둔다.
     *
     * <p>주문으로 묶인 금액이 있으면 주문가능금액만 그만큼 줄어든다.
     */
    public static Deposits from(Balance balance) {
        if (balance == null) {
            throw new IllegalArgumentException("잔고는 필수입니다.");
        }
        return new Deposits(balance.cash(), balance.cash(),
                balance.available(), balance.available());
    }

    /** 미수가 발생했는지. D+2 예수금이 음수라는 뜻이다. */
    public boolean hasShortfall() {
        return settledCash.signum() < 0;
    }

    /**
     * 미수 금액. 미수가 없으면 0.
     *
     * <p>결제일까지 채워 넣지 않으면 반대매매 대상이 되므로 감추지 않고 값으로 남긴다.
     */
    public BigDecimal shortfall() {
        return hasShortfall() ? settledCash.negate() : BigDecimal.ZERO;
    }
}
