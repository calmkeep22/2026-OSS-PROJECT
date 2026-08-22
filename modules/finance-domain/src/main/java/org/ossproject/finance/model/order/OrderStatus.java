package org.ossproject.finance.model.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * 주문 생명주기 상태.
 *
 * <p>증권사 주문은 접수와 체결이 분리되어 있다. REST로 접수한 뒤 체결은 WebSocket으로
 * 뒤늦게 통보되므로, 주문 객체는 상태를 가지고 시간에 따라 전이한다.
 */
public enum OrderStatus {
    /** 로컬에서 생성되었으나 아직 증권사에 접수되지 않음. */
    NEW("접수 대기"),
    /** 증권사가 주문을 접수함. 아직 체결 없음. */
    ACCEPTED("접수 완료"),
    /** 일부 수량만 체결됨. */
    PARTIALLY_FILLED("부분 체결"),
    /** 전량 체결됨. */
    FILLED("전량 체결"),
    /** 사용자 또는 시스템이 취소함. */
    CANCELLED("주문 취소"),
    /** 증권사가 거부함. */
    REJECTED("주문 거부");

    private static final Set<OrderStatus> TERMINAL = EnumSet.of(FILLED, CANCELLED, REJECTED);

    private final String displayName;

    OrderStatus(String displayName) {
        this.displayName = displayName;
    }

    /** 화면 표시와 음성 안내에 그대로 사용할 수 있는 한국어 이름. */
    public String displayName() {
        return displayName;
    }

    /** 더 이상 상태가 변하지 않는 최종 상태인지 여부. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** {@code next} 로의 전이가 허용되는지 여부. */
    public boolean canTransitionTo(OrderStatus next) {
        if (next == null || isTerminal()) {
            return false;
        }
        return switch (this) {
            case NEW -> next == ACCEPTED || next == REJECTED || next == CANCELLED;
            case ACCEPTED -> next == PARTIALLY_FILLED || next == FILLED
                    || next == CANCELLED || next == REJECTED;
            case PARTIALLY_FILLED -> next == PARTIALLY_FILLED || next == FILLED || next == CANCELLED;
            default -> false;
        };
    }
}
