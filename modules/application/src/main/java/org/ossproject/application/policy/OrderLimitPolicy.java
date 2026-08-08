package org.ossproject.application.policy;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 주문 안전장치 정책.
 *
 * <p>화면을 눈으로 확인하기 어려운 사용자가 실수로 같은 주문을 여러 번 보내거나 자릿수를
 * 잘못 입력하는 상황을 막는다.
 *
 * @param maxOrderAmount     단일 주문 최대 금액. {@code null} 이면 제한 없음
 * @param maxDailyAmount     하루 누적 주문 최대 금액. {@code null} 이면 제한 없음
 * @param maxOrdersPerMinute 분당 최대 주문 건수. 0 이하이면 제한 없음
 * @param duplicateWindow    같은 내용의 주문을 다시 받지 않는 시간. {@link Duration#ZERO} 이면 제한 없음
 */
public record OrderLimitPolicy(
        BigDecimal maxOrderAmount,
        BigDecimal maxDailyAmount,
        int maxOrdersPerMinute,
        Duration duplicateWindow
) {
    public OrderLimitPolicy {
        if (maxOrderAmount != null && maxOrderAmount.signum() <= 0) {
            throw new IllegalArgumentException("단일 주문 한도는 0보다 커야 합니다.");
        }
        if (maxDailyAmount != null && maxDailyAmount.signum() <= 0) {
            throw new IllegalArgumentException("일일 주문 한도는 0보다 커야 합니다.");
        }
        if (duplicateWindow == null || duplicateWindow.isNegative()) {
            throw new IllegalArgumentException("중복 주문 차단 시간은 0 이상이어야 합니다.");
        }
        if (maxOrderAmount != null && maxDailyAmount != null
                && maxOrderAmount.compareTo(maxDailyAmount) > 0) {
            throw new IllegalArgumentException("단일 주문 한도가 일일 한도보다 클 수 없습니다.");
        }
    }

    /**
     * 기본 정책. 단일 1천만원, 일일 5천만원, 분당 10건, 같은 주문은 5초 안에 재접수 불가.
     */
    public static OrderLimitPolicy defaults() {
        return new OrderLimitPolicy(
                new BigDecimal("10000000"),
                new BigDecimal("50000000"),
                10,
                Duration.ofSeconds(5));
    }

    /** 테스트와 모의투자용. 아무것도 막지 않는다. */
    public static OrderLimitPolicy unlimited() {
        return new OrderLimitPolicy(null, null, 0, Duration.ZERO);
    }
}
