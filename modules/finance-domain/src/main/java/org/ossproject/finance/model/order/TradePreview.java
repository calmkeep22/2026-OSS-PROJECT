package org.ossproject.finance.model.order;

import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 주문 접수 직전에 사용자에게 확인시키는 미리보기.
 *
 * <p>{@link #describe()} 는 음성으로 그대로 읽어 줄 수 있는 문장을 만든다. 주문 전
 * 음성 재확인 흐름이 이 문장을 사용한다.
 *
 * @param costs 수수료와 세금. 요율을 모르면 {@link TradeCosts#unknown()}
 */
public record TradePreview(
        OrderCommand command,
        BigDecimal referencePrice,
        BigDecimal estimatedAmount,
        BigDecimal availableCashBefore,
        BigDecimal availableCashAfter,
        TradeCosts costs
) {
    public TradePreview {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        if (estimatedAmount == null) {
            throw new IllegalArgumentException("예상 주문 금액은 필수입니다.");
        }
        costs = costs == null ? TradeCosts.unknown() : costs;
    }

    /** 비용 없이 만드는 미리보기. 요율을 모르는 경우다. */
    public TradePreview(OrderCommand command, BigDecimal referencePrice, BigDecimal estimatedAmount,
                        BigDecimal availableCashBefore, BigDecimal availableCashAfter) {
        this(command, referencePrice, estimatedAmount, availableCashBefore, availableCashAfter, null);
    }

    /**
     * 비용까지 반영해 실제로 오갈 금액.
     *
     * <p>매수는 주문 금액보다 더 나가고 매도는 덜 들어온다. 요율을 모르면 주문 금액을
     * 그대로 돌려준다.
     */
    public BigDecimal settlementAmount() {
        return costs.settlementAmount(command.side(), estimatedAmount);
    }

    /** 음성 안내용 한국어 문장. */
    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append(command.name()).append(' ')
                .append(command.quantity()).append("주를 ")
                .append(command.type().displayName());
        if (command.type() == OrderType.LIMIT) {
            sb.append(' ').append(formatWon(command.limitPrice())).append("원에");
        }
        sb.append(' ').append(command.side().displayName()).append("합니다. ")
                .append("예상 금액 ").append(formatWon(estimatedAmount)).append("원, ");
        // 총액만 읽으면 체결 뒤에야 차이를 알게 된다. 되돌릴 수 없는 동작 직전이라 더 그렇다.
        if (costs.isKnown()) {
            sb.append("수수료와 세금 ").append(formatWon(costs.total())).append("원을 더해 ")
                    .append(command.side() == OrderSide.SELL ? "받을 금액 " : "낼 금액 ")
                    .append(formatWon(settlementAmount())).append("원, ");
        } else {
            sb.append("수수료율이 설정되지 않아 비용은 계산하지 않았습니다. ");
        }
        if (availableCashAfter != null) {
            sb.append("주문 후 주문가능금액 ").append(formatWon(availableCashAfter)).append("원입니다.");
        } else {
            sb.append("주문가능금액은 확인되지 않았습니다.");
        }
        return sb.toString();
    }

    private static String formatWon(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return NumberFormat.getNumberInstance(Locale.KOREA).format(value);
    }
}
