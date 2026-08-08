package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 주문 접수 직전에 사용자에게 확인시키는 미리보기.
 *
 * <p>{@link #describe()} 는 음성으로 그대로 읽어 줄 수 있는 문장을 만든다. 주문 전
 * 음성 재확인 흐름이 이 문장을 사용한다.
 */
public record TradePreview(
        OrderCommand command,
        BigDecimal referencePrice,
        BigDecimal estimatedAmount,
        BigDecimal availableCashBefore,
        BigDecimal availableCashAfter
) {
    public TradePreview {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        if (estimatedAmount == null) {
            throw new IllegalArgumentException("예상 주문 금액은 필수입니다.");
        }
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
