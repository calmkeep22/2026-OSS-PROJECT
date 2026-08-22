package org.ossproject.desktop.orderbook;

import org.ossproject.finance.model.orderbook.DepthCurve;
import org.ossproject.finance.model.orderbook.DepthPoint;
import org.ossproject.finance.model.orderbook.OrderBook;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 물량이 몰린 지점을 문장으로 알린다.
 *
 * <p>벽의 위치는 호가창에서 가장 중요한 정보 중 하나인데, 그래프에서는 색으로만 구분된다.
 * 색은 화면을 볼 수 없는 사용자에게 전달되지 않는다.
 *
 * <p>갱신마다 읽으면 소음이 된다. 벽이 새로 생기거나 사라졌을 때만 알린다. 잔량이
 * 오르내려도 같은 자리에 벽이 계속 있으면 다시 말하지 않는다.
 */
public final class WallAnnouncer {

    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.KOREA);

    /** 직전에 알린 벽의 위치. 가격과 방향으로 구분한다. */
    private Set<String> announced = Set.of();

    /**
     * 호가창을 보고 알릴 문장을 만든다.
     *
     * @return 벽 구성이 달라졌으면 알릴 문장. 그대로면 비어 있다
     */
    public Optional<String> onOrderBook(OrderBook book) {
        if (book == null || book.isEmpty()) {
            announced = Set.of();
            return Optional.empty();
        }

        DepthCurve curve = DepthCurve.from(book);
        List<String> keys = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        collect(curve.askSide(), "매도", keys, phrases);
        collect(curve.bidSide(), "매수", keys, phrases);

        Set<String> current = new LinkedHashSet<>(keys);
        if (current.equals(announced)) {
            return Optional.empty();
        }
        announced = current;

        if (phrases.isEmpty()) {
            // 있던 벽이 사라진 경우다. 사라진 것도 알려야 판단이 바뀐다.
            return Optional.of("몰린 물량이 사라졌습니다.");
        }
        return Optional.of("물량이 몰린 곳. " + String.join(", ", phrases) + ".");
    }

    /** 종목이 바뀌면 이전 종목의 벽을 기억하고 있으면 안 된다. */
    public void reset() {
        announced = Set.of();
    }

    private static void collect(List<DepthPoint> side, String label,
                                List<String> keys, List<String> phrases) {
        for (DepthPoint point : side) {
            if (!point.wall()) {
                continue;
            }
            keys.add(label + "@" + point.price().toPlainString());
            phrases.add(label + " " + price(point.price()) + "에 " + NUMBERS.format(point.levelSize()) + "주");
        }
    }

    private static String price(BigDecimal value) {
        return NUMBERS.format(value) + "원";
    }
}
