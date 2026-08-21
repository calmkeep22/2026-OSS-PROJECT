package org.ossproject.desktop.presentation;

import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.StockDetail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 값을 사람이 읽을 글자로 바꾼다.
 *
 * <p>화면에 보이는 글자와 읽어 주는 문장이 여기서 함께 나온다. 두 곳에서 따로 만들면
 * 스크린리더 사용자가 화면과 다른 값을 듣게 된다.
 */
public final class Formatters {

    private static final NumberFormat KRW = NumberFormat.getIntegerInstance(Locale.KOREA);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private Formatters() {
    }

    public static String won(BigDecimal value) {
        return KRW.format(value) + "원";
    }

    /** 부호를 앞에 붙인 금액. 손익처럼 방향이 뜻을 갖는 값에 쓴다. */
    public static String signedWon(BigDecimal value) {
        return (value.signum() >= 0 ? "+" : "-") + won(value.abs());
    }

    /** 조회 결과의 등락률을 부호와 함께 표기한다. 값을 새로 만들지 않는다. */
    public static String signedChangeRate(StockDetail detail) {
        BigDecimal rate = detail.changeRate().setScale(2, RoundingMode.HALF_UP);
        String sign = detail.direction() == PriceDirection.DOWN ? "-" : rate.signum() > 0 ? "+" : "";
        return sign + rate.abs().toPlainString() + "%";
    }

    /**
     * 총자산이 어디서 온 값인지.
     *
     * <p>증권사가 계산한 값과 앱이 더한 값은 다를 수 있다. 어느 쪽인지 밝히지 않으면
     * 사용자가 증권사 화면과 대조할 때 어느 숫자를 믿어야 할지 알 수 없다.
     */
    public static String assetsSource(Account account) {
        return account.valuationReportedByBroker() ? "증권사 제공 값" : "앱에서 합산한 값";
    }

    public static String orderTime(Order order) {
        return LocalDateTime.ofInstant(order.createdAt(), SEOUL).format(ORDER_TIME);
    }
}
