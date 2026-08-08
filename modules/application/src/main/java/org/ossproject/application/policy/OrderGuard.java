package org.ossproject.application.policy;

import org.ossproject.finance.model.OrderCommand;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 주문 한도와 중복 주문을 막는 안전장치.
 *
 * <p>{@link #authorize} 는 검사와 기록을 한 번에 처리한다. 통과한 주문만 카운트에 반영되므로
 * 거부된 주문이 한도를 갉아먹지 않는다.
 *
 * <p>여러 스레드에서 호출될 수 있어 전체를 동기화한다. 주문은 초당 수천 건이 아니라
 * 사람이 손으로 넣는 빈도라 경합은 문제가 되지 않는다.
 */
public final class OrderGuard {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    private final OrderLimitPolicy policy;
    private final Clock clock;

    private final Deque<Instant> recentOrderTimes = new ArrayDeque<>();
    private final Map<String, Instant> recentFingerprints = new HashMap<>();

    private LocalDate dailyTotalDate;
    private BigDecimal dailyTotal = BigDecimal.ZERO;

    public OrderGuard(OrderLimitPolicy policy) {
        this(policy, Clock.system(MARKET_ZONE));
    }

    /** 테스트에서 시간을 고정하려면 이 생성자를 쓴다. */
    public OrderGuard(OrderLimitPolicy policy, Clock clock) {
        if (policy == null) {
            throw new IllegalArgumentException("주문 정책은 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.policy = policy;
        this.clock = clock;
        this.dailyTotalDate = LocalDate.now(clock);
    }

    /**
     * 주문을 허가한다. 통과하면 사용량에 반영되고, 막히면 예외를 던진다.
     *
     * @throws OrderRejectedException 한도 초과, 속도 제한, 중복 주문인 경우
     */
    public synchronized void authorize(OrderCommand command, BigDecimal estimatedAmount) {
        if (command == null) {
            throw new IllegalArgumentException("주문 요청은 필수입니다.");
        }
        if (estimatedAmount == null || estimatedAmount.signum() <= 0) {
            throw new IllegalArgumentException("예상 주문 금액은 0보다 커야 합니다.");
        }

        Instant now = clock.instant();
        rollOverDayIfNeeded();
        purgeExpired(now);

        checkOrderAmount(estimatedAmount);
        checkDailyAmount(estimatedAmount);
        checkRateLimit();
        checkDuplicate(command, now);

        recentOrderTimes.addLast(now);
        recentFingerprints.put(fingerprint(command), now);
        dailyTotal = dailyTotal.add(estimatedAmount);
    }

    /** 오늘 누적 주문 금액. */
    public synchronized BigDecimal dailyTotal() {
        rollOverDayIfNeeded();
        return dailyTotal;
    }

    /** 사용자가 정책을 바꾸거나 모의투자로 전환할 때 사용량을 비운다. */
    public synchronized void reset() {
        recentOrderTimes.clear();
        recentFingerprints.clear();
        dailyTotal = BigDecimal.ZERO;
        dailyTotalDate = LocalDate.now(clock);
    }

    public OrderLimitPolicy policy() {
        return policy;
    }

    private void checkOrderAmount(BigDecimal estimatedAmount) {
        BigDecimal limit = policy.maxOrderAmount();
        if (limit != null && estimatedAmount.compareTo(limit) > 0) {
            throw new OrderRejectedException(OrderRejectedException.Reason.ORDER_AMOUNT_EXCEEDED,
                    "주문 금액 " + won(estimatedAmount) + "원이 단일 주문 한도 "
                            + won(limit) + "원을 넘습니다.");
        }
    }

    private void checkDailyAmount(BigDecimal estimatedAmount) {
        BigDecimal limit = policy.maxDailyAmount();
        if (limit == null) {
            return;
        }
        BigDecimal projected = dailyTotal.add(estimatedAmount);
        if (projected.compareTo(limit) > 0) {
            throw new OrderRejectedException(OrderRejectedException.Reason.DAILY_AMOUNT_EXCEEDED,
                    "이 주문을 포함하면 오늘 누적 " + won(projected) + "원으로 일일 한도 "
                            + won(limit) + "원을 넘습니다.");
        }
    }

    private void checkRateLimit() {
        int limit = policy.maxOrdersPerMinute();
        if (limit > 0 && recentOrderTimes.size() >= limit) {
            throw new OrderRejectedException(OrderRejectedException.Reason.RATE_LIMIT_EXCEEDED,
                    "1분 안에 주문이 " + limit + "건을 넘었습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private void checkDuplicate(OrderCommand command, Instant now) {
        Duration window = policy.duplicateWindow();
        if (window.isZero()) {
            return;
        }
        Instant last = recentFingerprints.get(fingerprint(command));
        if (last != null && Duration.between(last, now).compareTo(window) < 0) {
            throw new OrderRejectedException(OrderRejectedException.Reason.DUPLICATE_ORDER,
                    "같은 내용의 주문이 " + window.toSeconds() + "초 안에 이미 접수되었습니다. "
                            + command.name() + " " + command.quantity() + "주 "
                            + command.side().displayName() + " 주문입니다.");
        }
    }

    private void rollOverDayIfNeeded() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(dailyTotalDate)) {
            dailyTotalDate = today;
            dailyTotal = BigDecimal.ZERO;
        }
    }

    private void purgeExpired(Instant now) {
        Instant rateCutoff = now.minus(Duration.ofMinutes(1));
        while (!recentOrderTimes.isEmpty() && recentOrderTimes.peekFirst().isBefore(rateCutoff)) {
            recentOrderTimes.removeFirst();
        }

        Duration window = policy.duplicateWindow();
        if (window.isZero()) {
            recentFingerprints.clear();
            return;
        }
        Instant duplicateCutoff = now.minus(window);
        Iterator<Map.Entry<String, Instant>> iterator = recentFingerprints.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isBefore(duplicateCutoff)) {
                iterator.remove();
            }
        }
    }

    private static String fingerprint(OrderCommand command) {
        return String.join("|", command.symbol(), command.side().name(), command.type().name(),
                Long.toString(command.quantity()),
                command.limitPrice() == null ? "MARKET" : command.limitPrice().stripTrailingZeros().toPlainString());
    }

    private static String won(BigDecimal value) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(value);
    }
}
