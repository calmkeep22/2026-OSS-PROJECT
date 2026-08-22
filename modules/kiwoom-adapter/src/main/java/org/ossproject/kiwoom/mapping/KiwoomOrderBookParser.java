package org.ossproject.kiwoom.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.OrderBookLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 키움 호가 응답을 {@link OrderBook} 으로 옮긴다.
 *
 * <p>두 가지 형식을 모두 다룬다.
 * <ul>
 *   <li>REST {@code ka10004} — {@code sel_fpr_bid}, {@code sel_2th_pre_bid} 같은 이름 필드</li>
 *   <li>실시간 {@code 0D} — FID 번호를 키로 쓰는 {@code values} 맵</li>
 * </ul>
 *
 * <p>키움 응답에는 부호와 천 단위 구분자가 섞여 온다. 매도 호가는 음수 부호가 붙어 오는
 * 경우가 있어 절대값을 취한다. 가격이 음수인 호가는 존재하지 않으므로 안전하다.
 */
public final class KiwoomOrderBookParser {

    /** 거래소가 공개하는 최대 호가 단계. */
    public static final int MAX_DEPTH = 10;

    private KiwoomOrderBookParser() {
    }

    // ------------------------------------------------------------------
    // REST (ka10004)
    // ------------------------------------------------------------------

    /**
     * {@code ka10004} 응답을 파싱한다.
     *
     * <p>1단계만 이름이 다르다. {@code sel_1th_pre_bid} 가 아니라 {@code sel_fpr_bid}(최우선)
     * 이므로 별도로 처리한다. 이 차이를 놓치면 1호가가 통째로 비어 버린다.
     */
    public static OrderBook fromRest(String symbol, JsonNode root, Instant timestamp) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (root == null) {
            throw new IllegalArgumentException("응답은 필수입니다.");
        }

        List<OrderBookLevel> levels = new ArrayList<>(MAX_DEPTH);
        for (int level = 1; level <= MAX_DEPTH; level++) {
            BigDecimal askPrice = decimal(root, restField("sel", level, "bid"));
            long askSize = longValue(root, restField("sel", level, "req"));
            long askDelta = longValue(root, restDeltaField("sel", level));

            BigDecimal bidPrice = decimal(root, restField("buy", level, "bid"));
            long bidSize = longValue(root, restField("buy", level, "req"));
            long bidDelta = longValue(root, restDeltaField("buy", level));

            if (askPrice == null && bidPrice == null && askSize == 0 && bidSize == 0) {
                continue;
            }
            levels.add(new OrderBookLevel(level, askPrice, askSize, askDelta,
                    bidPrice, bidSize, bidDelta));
        }

        long totalAsk = longValue(root, "tot_sel_req");
        long totalBid = longValue(root, "tot_buy_req");
        if (totalAsk == 0 && totalBid == 0) {
            return OrderBook.of(symbol, levels, timestamp);
        }
        return new OrderBook(symbol, levels, totalAsk, totalBid, timestamp);
    }

    /** {@code sel_fpr_bid}, {@code sel_2th_pre_bid} … {@code sel_10th_pre_bid} */
    private static String restField(String side, int level, String suffix) {
        if (level == 1) {
            return side + "_fpr_" + suffix;
        }
        return side + "_" + level + "th_pre_" + suffix;
    }

    /** 잔량 직전 대비. 1단계도 {@code sel_1th_pre_req_pre} 형태를 쓴다. */
    private static String restDeltaField(String side, int level) {
        return side + "_" + level + "th_pre_req_pre";
    }

    // ------------------------------------------------------------------
    // 실시간 (0D 주식호가잔량)
    // ------------------------------------------------------------------

    /**
     * 실시간 {@code 0D} 의 {@code values} 맵을 파싱한다.
     *
     * <p>FID 번호가 산술적으로 규칙적이라 반복문으로 처리한다. 60개 필드를 하나씩
     * 적어 두면 오타 하나로 특정 단계만 조용히 비어 버린다.
     *
     * <pre>
     *   매도호가 n      = 40 + n     (41~50)
     *   매수호가 n      = 50 + n     (51~60)
     *   매도호가수량 n  = 60 + n     (61~70)
     *   매수호가수량 n  = 70 + n     (71~80)
     *   매도직전대비 n  = 80 + n     (81~90)
     *   매수직전대비 n  = 90 + n     (91~100)
     * </pre>
     */
    public static OrderBook fromRealtime(String symbol, JsonNode values, Instant timestamp) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (values == null) {
            throw new IllegalArgumentException("실시간 값은 필수입니다.");
        }

        List<OrderBookLevel> levels = new ArrayList<>(MAX_DEPTH);
        for (int level = 1; level <= MAX_DEPTH; level++) {
            BigDecimal askPrice = decimal(values, fid(40 + level));
            BigDecimal bidPrice = decimal(values, fid(50 + level));
            long askSize = longValue(values, fid(60 + level));
            long bidSize = longValue(values, fid(70 + level));
            long askDelta = longValue(values, fid(80 + level));
            long bidDelta = longValue(values, fid(90 + level));

            if (askPrice == null && bidPrice == null && askSize == 0 && bidSize == 0) {
                continue;
            }
            levels.add(new OrderBookLevel(level, askPrice, askSize, askDelta,
                    bidPrice, bidSize, bidDelta));
        }

        long totalAsk = longValue(values, "121");
        long totalBid = longValue(values, "125");
        if (totalAsk == 0 && totalBid == 0) {
            return OrderBook.of(symbol, levels, timestamp);
        }
        return new OrderBook(symbol, levels, totalAsk, totalBid, timestamp);
    }

    private static String fid(int number) {
        return Integer.toString(number);
    }

    // ------------------------------------------------------------------
    // 값 읽기
    // ------------------------------------------------------------------

    /**
     * 가격을 읽는다. 값이 없거나 0이면 {@code null} 을 돌려준다.
     *
     * <p>키움은 매도 호가에 음수 부호를 붙여 보내는 경우가 있어 절대값을 취한다.
     */
    private static BigDecimal decimal(JsonNode parent, String field) {
        String raw = text(parent, field);
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw).abs();
            return value.signum() == 0 ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 잔량을 읽는다. 부호를 유지해야 직전 대비 증감을 표현할 수 있다. */
    private static long longValue(JsonNode parent, String field) {
        String raw = text(parent, field);
        if (raw == null) {
            return 0L;
        }
        try {
            return new BigDecimal(raw).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 천 단위 구분자와 더하기 부호를 걷어낸다. */
    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText();
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(",", "").replace("+", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
