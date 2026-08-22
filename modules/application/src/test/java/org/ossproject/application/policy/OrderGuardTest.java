package org.ossproject.application.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.order.OrderCommand;
import org.ossproject.finance.model.OrderSide;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderGuardTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant START = Instant.parse("2026-08-08T01:00:00Z");

    /** 테스트에서 시간을 임의로 밀 수 있는 시계. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private OrderCommand buy(long quantity, String price) {
        return OrderCommand.limit("005930", "삼성전자", OrderSide.BUY, quantity, new BigDecimal(price));
    }

    @Test
    @DisplayName("단일 주문 한도를 넘으면 거부한다")
    void rejectsOverSingleLimit() {
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                new BigDecimal("1000000"), null, 0, Duration.ZERO), new MutableClock(START));

        OrderRejectedException thrown = assertThrows(OrderRejectedException.class,
                () -> guard.authorize(buy(100, "70000"), new BigDecimal("7000000")));

        assertEquals(OrderRejectedException.Reason.ORDER_AMOUNT_EXCEEDED, thrown.reason());
    }

    @Test
    @DisplayName("거부된 주문은 일일 누적에 반영되지 않는다")
    void rejectedOrderDoesNotConsumeDailyTotal() {
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                new BigDecimal("1000000"), new BigDecimal("5000000"), 0, Duration.ZERO),
                new MutableClock(START));

        assertThrows(OrderRejectedException.class,
                () -> guard.authorize(buy(100, "70000"), new BigDecimal("7000000")));

        assertEquals(0, BigDecimal.ZERO.compareTo(guard.dailyTotal()));
    }

    @Test
    @DisplayName("일일 누적 한도를 넘으면 거부한다")
    void rejectsOverDailyLimit() {
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                new BigDecimal("1000000"), new BigDecimal("2500000"), 0, Duration.ZERO),
                new MutableClock(START));

        guard.authorize(buy(10, "100000"), new BigDecimal("1000000"));
        guard.authorize(buy(10, "100000"), new BigDecimal("1000000"));

        OrderRejectedException thrown = assertThrows(OrderRejectedException.class,
                () -> guard.authorize(buy(10, "100000"), new BigDecimal("1000000")));

        assertEquals(OrderRejectedException.Reason.DAILY_AMOUNT_EXCEEDED, thrown.reason());
        assertEquals(0, new BigDecimal("2000000").compareTo(guard.dailyTotal()));
    }

    @Test
    @DisplayName("같은 주문을 차단 시간 안에 다시 보내면 거부한다")
    void rejectsDuplicateWithinWindow() {
        MutableClock clock = new MutableClock(START);
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                null, null, 0, Duration.ofSeconds(5)), clock);

        guard.authorize(buy(10, "70000"), new BigDecimal("700000"));

        clock.advance(Duration.ofSeconds(2));
        OrderRejectedException thrown = assertThrows(OrderRejectedException.class,
                () -> guard.authorize(buy(10, "70000"), new BigDecimal("700000")));
        assertEquals(OrderRejectedException.Reason.DUPLICATE_ORDER, thrown.reason());

        clock.advance(Duration.ofSeconds(4));
        assertDoesNotThrow(() -> guard.authorize(buy(10, "70000"), new BigDecimal("700000")));
    }

    @Test
    @DisplayName("수량이 다르면 중복 주문이 아니다")
    void allowsDifferentQuantity() {
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                null, null, 0, Duration.ofSeconds(5)), new MutableClock(START));

        guard.authorize(buy(10, "70000"), new BigDecimal("700000"));

        assertDoesNotThrow(() -> guard.authorize(buy(11, "70000"), new BigDecimal("770000")));
    }

    @Test
    @DisplayName("분당 주문 건수를 넘으면 거부하고 1분이 지나면 다시 허용한다")
    void enforcesRateLimit() {
        MutableClock clock = new MutableClock(START);
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                null, null, 2, Duration.ZERO), clock);

        guard.authorize(buy(1, "70000"), new BigDecimal("70000"));
        clock.advance(Duration.ofSeconds(1));
        guard.authorize(buy(2, "70000"), new BigDecimal("140000"));
        clock.advance(Duration.ofSeconds(1));

        OrderRejectedException thrown = assertThrows(OrderRejectedException.class,
                () -> guard.authorize(buy(3, "70000"), new BigDecimal("210000")));
        assertEquals(OrderRejectedException.Reason.RATE_LIMIT_EXCEEDED, thrown.reason());

        clock.advance(Duration.ofMinutes(1));
        assertDoesNotThrow(() -> guard.authorize(buy(3, "70000"), new BigDecimal("210000")));
    }

    @Test
    @DisplayName("날짜가 바뀌면 일일 누적이 초기화된다")
    void resetsDailyTotalOnNewDay() {
        MutableClock clock = new MutableClock(START);
        OrderGuard guard = new OrderGuard(new OrderLimitPolicy(
                null, new BigDecimal("2000000"), 0, Duration.ZERO), clock);

        guard.authorize(buy(10, "100000"), new BigDecimal("1500000"));
        assertEquals(0, new BigDecimal("1500000").compareTo(guard.dailyTotal()));

        clock.advance(Duration.ofDays(1));

        assertEquals(0, BigDecimal.ZERO.compareTo(guard.dailyTotal()));
        assertDoesNotThrow(() -> guard.authorize(buy(10, "100000"), new BigDecimal("1500000")));
    }

    @Test
    @DisplayName("제한 없음 정책은 아무것도 막지 않는다")
    void unlimitedPolicyAllowsEverything() {
        MutableClock clock = new MutableClock(START);
        OrderGuard guard = new OrderGuard(OrderLimitPolicy.unlimited(), clock);

        for (int i = 0; i < 50; i++) {
            int quantity = i + 1;
            assertDoesNotThrow(() -> guard.authorize(
                    buy(quantity, "70000"), new BigDecimal("999999999")));
        }
    }
}
