package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * 체결 한 건.
 *
 * <p>{@link Quote} 는 현재가와 누적 거래량만 담는다. 체결 목록을 만들려면 이 체결이 몇
 * 주였는지, 매수와 매도 중 어느 쪽이 주도했는지가 필요하다.
 *
 * <p>호가는 주문이 걸려 있다는 뜻일 뿐 거래가 이루어진 것이 아니다. 체결 흐름을 보아야
 * 지금 이 종목이 실제로 거래되고 있는지 알 수 있다.
 *
 * @param quantity 이 체결의 수량. 누적이 아니다
 * @param side     체결을 주도한 쪽. 매수 호가를 쳐서 사면 매수다
 */
public record Trade(
        String symbol,
        BigDecimal price,
        long quantity,
        OrderSide side,
        Instant timestamp
) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public Trade {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("체결가는 0보다 커야 합니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("체결 수량은 0보다 커야 합니다.");
        }
        if (side == null) {
            throw new IllegalArgumentException("체결 방향은 필수입니다.");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("체결 시각은 필수입니다.");
        }
    }

    /** 화면에 그대로 쓰는 시:분:초. */
    public String timeText() {
        return TIME.format(timestamp.atZone(SEOUL));
    }

    /**
     * 한 건을 읽어 줄 문장.
     *
     * <p>매수와 매도를 색으로만 구분하면 전달되지 않는 사용자가 있다. 말로 함께 적는다.
     */
    public String describe() {
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.KOREA);
        return timeText() + ", " + numbers.format(price) + "원, "
                + numbers.format(quantity) + "주, " + side.displayName();
    }
}
