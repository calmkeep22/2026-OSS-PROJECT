package org.ossproject.finance.model;

import java.math.BigDecimal;

/**
 * 고정 가격 격자의 한 행.
 *
 * <p>{@code askBarRatio}·{@code bidBarRatio} 는 0.0~1.0 으로 정규화된 막대 길이다.
 * 화면 계층은 이 값에 막대 최대 길이를 곱하기만 하면 되고, 정규화 규칙을 알 필요가 없다.
 *
 * @param price       이 행의 가격. 격자가 고정되어 있는 동안 바뀌지 않는다
 * @param askSize     매도 잔량
 * @param bidSize     매수 잔량
 * @param askDelta    매도 잔량 직전 대비 증감
 * @param bidDelta    매수 잔량 직전 대비 증감
 * @param askBarRatio 매도 막대 길이 비율
 * @param bidBarRatio 매수 막대 길이 비율
 * @param currentPriceRow 기준가가 있는 행인지 여부
 */
public record PriceLadderRow(
        BigDecimal price,
        long askSize,
        long bidSize,
        long askDelta,
        long bidDelta,
        double askBarRatio,
        double bidBarRatio,
        boolean currentPriceRow
) {
    public PriceLadderRow {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다.");
        }
        if (askSize < 0 || bidSize < 0) {
            throw new IllegalArgumentException("잔량은 0 이상이어야 합니다.");
        }
        if (askBarRatio < 0.0 || askBarRatio > 1.0 || bidBarRatio < 0.0 || bidBarRatio > 1.0) {
            throw new IllegalArgumentException("막대 비율은 0.0 이상 1.0 이하여야 합니다.");
        }
    }

    /** 이 가격대에 아무 주문도 없는지 여부. */
    public boolean isEmpty() {
        return askSize == 0L && bidSize == 0L;
    }

    /**
     * 키보드로 이 행에 이동했을 때 읽어 줄 문장.
     *
     * <p>매도·매수를 색이 아니라 말로 구분하고, 증감이 있으면 함께 알린다.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder(price.toPlainString()).append("원");
        if (currentPriceRow) {
            sb.append(", 현재가");
        }
        if (askSize > 0) {
            sb.append(", 매도 ").append(askSize).append("주");
            appendDelta(sb, askDelta);
        }
        if (bidSize > 0) {
            sb.append(", 매수 ").append(bidSize).append("주");
            appendDelta(sb, bidDelta);
        }
        if (isEmpty()) {
            sb.append(", 잔량 없음");
        }
        return sb.toString();
    }

    private static void appendDelta(StringBuilder sb, long delta) {
        if (delta > 0) {
            sb.append(" ").append(delta).append("주 증가");
        } else if (delta < 0) {
            sb.append(" ").append(-delta).append("주 감소");
        }
    }
}
