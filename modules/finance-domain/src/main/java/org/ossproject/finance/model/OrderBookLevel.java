package org.ossproject.finance.model;

import java.math.BigDecimal;

/**
 * 호가 한 단계. 같은 단계의 매도·매수를 함께 담는다.
 *
 * <p>거래소는 매도 1~10단계와 매수 1~10단계를 따로 주지만, 화면은 가격을 세로축으로 놓고
 * 좌우로 나눠 보여 준다. 그래서 "단계" 단위로 묶어 두면 화면 계층이 다시 조립할 필요가 없다.
 *
 * <p>{@code askDelta}·{@code bidDelta} 는 직전 대비 잔량 증감이다. 증권사가 직접 주기 때문에
 * 우리가 이전 상태를 들고 있다가 계산할 필요가 없고, 잔량이 들어왔는지 빠졌는지를
 * 소리로 알리는 데 그대로 쓸 수 있다.
 *
 * @param level    1이 최우선 호가
 * @param askPrice 매도 호가. 해당 단계가 비어 있으면 {@code null}
 * @param askSize  매도 잔량
 * @param askDelta 매도 잔량 직전 대비 증감
 * @param bidPrice 매수 호가. 해당 단계가 비어 있으면 {@code null}
 * @param bidSize  매수 잔량
 * @param bidDelta 매수 잔량 직전 대비 증감
 */
public record OrderBookLevel(
        int level,
        BigDecimal askPrice,
        long askSize,
        long askDelta,
        BigDecimal bidPrice,
        long bidSize,
        long bidDelta
) {
    public OrderBookLevel {
        if (level < 1) {
            throw new IllegalArgumentException("호가 단계는 1 이상이어야 합니다.");
        }
        if (askSize < 0 || bidSize < 0) {
            throw new IllegalArgumentException("잔량은 0 이상이어야 합니다.");
        }
        if (askPrice != null && askPrice.signum() < 0) {
            throw new IllegalArgumentException("매도 호가는 0 이상이어야 합니다.");
        }
        if (bidPrice != null && bidPrice.signum() < 0) {
            throw new IllegalArgumentException("매수 호가는 0 이상이어야 합니다.");
        }
    }

    /** 잔량과 증감이 없는 단계. */
    public static OrderBookLevel of(int level, BigDecimal askPrice, long askSize,
                                    BigDecimal bidPrice, long bidSize) {
        return new OrderBookLevel(level, askPrice, askSize, 0L, bidPrice, bidSize, 0L);
    }

    public boolean hasAsk() {
        return askPrice != null && askPrice.signum() > 0;
    }

    public boolean hasBid() {
        return bidPrice != null && bidPrice.signum() > 0;
    }

    /** 이 단계에서 가장 큰 잔량. 막대 길이 정규화에 쓴다. */
    public long maxSize() {
        return Math.max(askSize, bidSize);
    }
}
