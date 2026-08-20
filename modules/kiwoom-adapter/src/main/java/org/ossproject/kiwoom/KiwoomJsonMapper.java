package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.SensitiveDataMasker;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.Deposits;
import org.ossproject.finance.model.ReportedValuation;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.Execution;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.OrderType;
import org.ossproject.finance.model.Position;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.StockDetail;
import org.ossproject.finance.model.PriceDirection;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 키움 응답 JSON을 도메인 객체로 옮긴다.
 *
 * <p>키움 값 표기에는 세 가지 특징이 있어 그대로 파싱하면 틀린다.
 *
 * <ul>
 *   <li>숫자에 부호가 붙는다. {@code "+70700"}, {@code "-600"}</li>
 *   <li>계좌 응답은 좌측을 0으로 채운다. {@code "000000017598258"}</li>
 *   <li>계좌 응답의 종목코드에는 접두어가 붙는다. {@code "A005930"} (A:주식, J:ELW, Q:ETN)</li>
 * </ul>
 *
 * <p>필요한 필드가 없거나 형식이 다르면 조용히 넘어가지 않고 {@link BrokerException} 을 던진다.
 * 금액이 잘못 파싱되어 엉뚱한 주문이 나가는 것보다 즉시 실패하는 편이 안전하다.
 */
public final class KiwoomJsonMapper {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public KiwoomJsonMapper(ObjectMapper objectMapper, Clock clock) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper 는 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body);
        } catch (Exception e) {
            throw new BrokerException("증권사 응답을 해석하지 못했습니다. "
                    + SensitiveDataMasker.mask(String.valueOf(e.getMessage())), e);
        }
    }

    // ------------------------------------------------------------------
    // 시세 (ka10001 주식기본정보요청)
    // ------------------------------------------------------------------

    /** 기본정보 응답을 실시간 틱 모양으로 옮긴다. 호가는 별도 TR 이라 비운다. */
    public Quote toQuote(JsonNode root) {
        BigDecimal current = decimal(root, "cur_prc");
        BigDecimal previousClose = optionalDecimal(root, "base_pric")
                .filter(value -> value.signum() > 0)
                .orElseGet(() -> previousCloseFrom(current, optionalDecimal(root, "pred_pre")));
        return new Quote(
                text(root, "stk_cd"),
                current.abs(),
                previousClose == null ? null : previousClose.abs(),
                null,
                null,
                0L,
                0L,
                optionalLong(root, "trde_qty").orElse(0L),
                clock.instant());
    }

    /**
     * 실시간 스트림 메시지를 틱으로 옮긴다.
     *
     * <p>WebSocket 실시간 규격은 REST 와 필드 이름이 다르고 아직 확인하지 못했다. 그때까지는
     * {@link KiwoomFieldMap} 의 대응표를 그대로 쓴다. 규격을 확인하면 이 메서드와
     * {@code JsonStreamProtocol} 을 함께 정리한다.
     */
    public Quote toFieldMappedQuote(JsonNode node, KiwoomFieldMap fields) {
        if (fields == null) {
            throw new IllegalArgumentException("필드 대응표는 필수입니다.");
        }
        return new Quote(
                text(node, fields.nameOf(KiwoomField.QUOTE_SYMBOL)),
                decimal(node, fields.nameOf(KiwoomField.QUOTE_PRICE)).abs(),
                optionalDecimal(node, fields.nameOf(KiwoomField.QUOTE_PREVIOUS_CLOSE)).orElse(null),
                optionalDecimal(node, fields.nameOf(KiwoomField.QUOTE_BID_PRICE)).orElse(null),
                optionalDecimal(node, fields.nameOf(KiwoomField.QUOTE_ASK_PRICE)).orElse(null),
                optionalLong(node, fields.nameOf(KiwoomField.QUOTE_BID_SIZE)).orElse(0L),
                optionalLong(node, fields.nameOf(KiwoomField.QUOTE_ASK_SIZE)).orElse(0L),
                optionalLong(node, fields.nameOf(KiwoomField.QUOTE_VOLUME)).orElse(0L),
                clock.instant());
    }

    /** 기본정보 응답을 화면용 상세로 옮긴다. */
    public StockDetail toStockDetail(JsonNode root) {
        BigDecimal current = decimal(root, "cur_prc").abs();
        BigDecimal change = optionalDecimal(root, "pred_pre").orElse(BigDecimal.ZERO);
        BigDecimal rate = optionalDecimal(root, "flu_rt").orElse(BigDecimal.ZERO);
        return new StockDetail(
                text(root, "stk_cd"),
                optionalText(root, "stk_nm").orElseGet(() -> text(root, "stk_cd")),
                current,
                change.abs(),
                rate.abs(),
                directionOf(root, change),
                optionalDecimal(root, "open_pric").map(BigDecimal::abs).orElse(current),
                optionalDecimal(root, "high_pric").map(BigDecimal::abs).orElse(current),
                optionalDecimal(root, "low_pric").map(BigDecimal::abs).orElse(current),
                optionalLong(root, "trde_qty").orElse(0L),
                clock.instant());
    }

    /**
     * 등락 방향.
     *
     * <p>{@code pre_sig} 는 1:상한가 2:상승 3:보합 4:하한가 5:하락 이다. 값이 없으면 전일대비
     * 부호로 판단한다.
     */
    private PriceDirection directionOf(JsonNode root, BigDecimal change) {
        Optional<String> sign = optionalText(root, "pre_sig").or(() -> optionalText(root, "pred_pre_sig"));
        if (sign.isPresent()) {
            return switch (sign.get().trim()) {
                case "1", "2" -> PriceDirection.UP;
                case "4", "5" -> PriceDirection.DOWN;
                case "3" -> PriceDirection.FLAT;
                default -> fromSignum(change);
            };
        }
        return fromSignum(change);
    }

    private static PriceDirection fromSignum(BigDecimal change) {
        if (change == null || change.signum() == 0) return PriceDirection.FLAT;
        return change.signum() > 0 ? PriceDirection.UP : PriceDirection.DOWN;
    }

    private static BigDecimal previousCloseFrom(BigDecimal current, Optional<BigDecimal> change) {
        return change.map(value -> current.abs().subtract(value)).filter(value -> value.signum() > 0)
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // 봉 (ka10080 분봉 / ka10081 일봉 / ka10082 주봉 / ka10083 월봉)
    // ------------------------------------------------------------------

    /**
     * 차트 응답을 오래된 것부터 정렬해 돌려준다.
     *
     * <p>키움은 최신 봉을 먼저 준다. 도메인 계약은 오름차순이므로 여기서 뒤집는다.
     * 봉의 종가는 {@code cur_prc} 다.
     */
    public List<Candle> toCandles(JsonNode root, CandleInterval interval) {
        JsonNode list = candleArray(root, interval);
        List<Candle> candles = new ArrayList<>(list.size());
        for (JsonNode node : list) {
            candles.add(toCandle(node, interval));
        }
        candles.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));
        return List.copyOf(candles);
    }

    private JsonNode candleArray(JsonNode root, CandleInterval interval) {
        String field = interval.isIntraday() ? "stk_min_pole_chart_qry" : chartFieldOf(interval);
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!node.isArray()) {
            throw new BrokerException("차트 응답의 " + field + " 는 배열이어야 합니다.");
        }
        return node;
    }

    private static String chartFieldOf(CandleInterval interval) {
        return switch (interval) {
            case DAY -> "stk_dt_pole_chart_qry";
            case WEEK -> "stk_stk_pole_chart_qry";
            case MONTH -> "stk_mth_pole_chart_qry";
            default -> throw new IllegalArgumentException("일/주/월봉이 아닙니다: " + interval);
        };
    }

    private Candle toCandle(JsonNode node, CandleInterval interval) {
        BigDecimal close = decimal(node, "cur_prc").abs();
        BigDecimal open = optionalDecimal(node, "open_pric").map(BigDecimal::abs).orElse(close);
        BigDecimal high = optionalDecimal(node, "high_pric").map(BigDecimal::abs).orElse(close);
        BigDecimal low = optionalDecimal(node, "low_pric").map(BigDecimal::abs).orElse(close);
        return new Candle(
                candleTimestamp(node, interval),
                interval,
                open,
                high.max(open).max(close),
                low.min(open).min(close),
                close,
                optionalLong(node, "trde_qty").orElse(0L));
    }

    /** 분봉은 {@code cntr_tm}({@code yyyyMMddHHmmss}), 그 외는 {@code dt}({@code yyyyMMdd}) 를 쓴다. */
    private Instant candleTimestamp(JsonNode node, CandleInterval interval) {
        if (interval.isIntraday()) {
            String raw = text(node, "cntr_tm").trim();
            try {
                if (raw.length() >= 14) {
                    return LocalDateTime.of(
                            Integer.parseInt(raw.substring(0, 4)),
                            Integer.parseInt(raw.substring(4, 6)),
                            Integer.parseInt(raw.substring(6, 8)),
                            Integer.parseInt(raw.substring(8, 10)),
                            Integer.parseInt(raw.substring(10, 12)),
                            Integer.parseInt(raw.substring(12, 14))).atZone(MARKET_ZONE).toInstant();
                }
            } catch (RuntimeException invalid) {
                throw new BrokerException("분봉 체결시간을 해석하지 못했습니다. 입력값 " + raw, invalid);
            }
            throw new BrokerException("분봉 체결시간 형식이 올바르지 않습니다. 입력값 " + raw);
        }
        String raw = text(node, "dt").trim();
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(raw.substring(0, 4)),
                    Integer.parseInt(raw.substring(4, 6)),
                    Integer.parseInt(raw.substring(6, 8)));
            return date.atStartOfDay(MARKET_ZONE).toInstant();
        } catch (RuntimeException invalid) {
            throw new BrokerException("봉 일자를 해석하지 못했습니다. 입력값 " + raw, invalid);
        }
    }

    // ------------------------------------------------------------------
    // 계좌 (kt00001 예수금 + kt00018 잔고)
    // ------------------------------------------------------------------

    /**
     * 예수금과 보유 종목을 한 계좌로 합친다.
     *
     * @param deposit kt00001 응답
     * @param balance kt00018 응답
     */
    public Account toAccount(String accountNo, JsonNode deposit, JsonNode balance) {
        Deposits deposits = toDeposits(deposit);
        List<Position> positions = new ArrayList<>();
        JsonNode holdings = balance == null ? null : balance.get("acnt_evlt_remn_indv_tot");
        if (holdings != null && holdings.isArray()) {
            for (JsonNode node : holdings) {
                long quantity = optionalLong(node, "rmnd_qty").orElse(0L);
                if (quantity <= 0) {
                    continue;
                }
                BigDecimal averagePrice = optionalDecimal(node, "pur_pric")
                        .map(BigDecimal::abs).orElse(BigDecimal.ZERO);
                BigDecimal currentPrice = optionalDecimal(node, "cur_prc")
                        .map(BigDecimal::abs).orElse(averagePrice);
                positions.add(new Position(
                        stripSecurityPrefix(text(node, "stk_cd")),
                        optionalText(node, "stk_nm").orElseGet(() -> text(node, "stk_cd")),
                        quantity,
                        0L,
                        averagePrice,
                        currentPrice,
                        // 수수료·세금이 반영된 값이라 직접 계산한 것보다 정확하다.
                        new ReportedValuation(
                                optionalDecimal(node, "pur_amt").orElse(null),
                                optionalDecimal(node, "evlt_amt").orElse(null),
                                optionalDecimal(node, "evltv_prft").orElse(null),
                                optionalDecimal(node, "prft_rt").orElse(null))));
            }
        }
        ReportedValuation totals = new ReportedValuation(
                optionalDecimal(balance, "tot_pur_amt").orElse(null),
                optionalDecimal(balance, "tot_evlt_amt").orElse(null),
                optionalDecimal(balance, "tot_evlt_pl").orElse(null),
                optionalDecimal(balance, "tot_prft_rt").orElse(null));
        return new Account(accountNo, Balance.of(deposits.cash()), positions, deposits, totals,
                optionalDecimal(balance, "prsm_dpst_aset_amt").orElse(null));
    }

    /**
     * 예수금 단계를 읽는다.
     *
     * <p>{@code entr}(예수금) 하나만 보면 안 된다. 국내 주식 대금은 D+2 에 결제되므로 매수
     * 당일에는 {@code entr} 이 줄지 않는다. 반면 산 종목은 잔고에 즉시 잡히기 때문에,
     * 총자산을 {@code entr} 로 계산하면 매수 금액이 현금과 주식 양쪽에 한 번씩 세어진다.
     *
     * <p>D+2 추정예수금은 <b>음수일 수 있다.</b> 증거금 매수로 미수가 나면 그렇다. 0 으로
     * 뭉개면 반대매매 위험을 감추게 되므로 부호를 그대로 둔다.
     *
     * <p>필드 이름은 모의투자 서버 응답으로 확인했다. 출금가능금액은 {@code wthd_alowa} 가
     * 아니라 {@code pymn_alow_amt}(지급가능금액) 다.
     *
     * <p>없는 필드는 순서대로 물러선다. 필드가 하나 빠졌다고 잔고를 0 원으로 보여 주는 것이
     * 더 나쁘다. 화면을 볼 수 없는 사용자에게 0 원은 계좌가 빈 것으로 읽힌다.
     */
    private Deposits toDeposits(JsonNode deposit) {
        BigDecimal cash = optionalDecimal(deposit, "entr")
                .orElse(BigDecimal.ZERO).max(BigDecimal.ZERO);
        BigDecimal settled = optionalDecimal(deposit, "d2_entra").orElse(cash);
        BigDecimal orderable = optionalDecimal(deposit, "ord_alow_amt")
                .orElse(settled).max(BigDecimal.ZERO);
        BigDecimal withdrawable = optionalDecimal(deposit, "pymn_alow_amt")
                .orElse(orderable).max(BigDecimal.ZERO);
        return new Deposits(cash, settled, orderable, withdrawable);
    }

    /** 계좌 응답의 {@code A005930} 형태에서 접두어를 떼어 낸다. */
    static String stripSecurityPrefix(String rawCode) {
        String value = rawCode.trim();
        if (value.length() == 7 && Character.isLetter(value.charAt(0))
                && value.substring(1).chars().allMatch(Character::isDigit)) {
            return value.substring(1);
        }
        return value;
    }

    // ------------------------------------------------------------------
    // 주문 (kt00009 계좌별주문체결현황요청)
    // ------------------------------------------------------------------

    public List<Order> toOrders(JsonNode root) {
        JsonNode list = root.get("acnt_ord_cntr_prst_array");
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Order> orders = new ArrayList<>();
        for (JsonNode node : list) {
            orders.add(toOrder(node));
        }
        return List.copyOf(orders);
    }

    /**
     * 주문 한 건을 도메인 주문으로 옮긴다.
     *
     * <p>키움은 주문 상태 코드를 따로 주지 않는다. 주문수량과 체결수량, 정정·취소구분으로
     * 상태를 판단한다. 체결 이력은 합성 한 건으로 표현하고, 개별 체결은 실시간 스트림이 채운다.
     */
    public Order toOrder(JsonNode node) {
        String orderId = text(node, "ord_no");
        String symbol = stripSecurityPrefix(text(node, "stk_cd"));
        String name = optionalText(node, "stk_nm").orElse(symbol);
        long quantity = optionalLong(node, "ord_qty").orElse(0L);
        long filled = optionalLong(node, "cntr_qty").orElse(0L);
        BigDecimal unitPrice = optionalDecimal(node, "ord_uv").map(BigDecimal::abs)
                .filter(value -> value.signum() > 0).orElse(null);
        OrderSide side = sideOf(node);
        OrderType type = unitPrice == null ? OrderType.MARKET : OrderType.LIMIT;

        if (quantity <= 0) {
            throw new BrokerException("주문 " + orderId + " 의 주문수량을 해석하지 못했습니다.");
        }

        OrderCommand command = new OrderCommand(symbol, name, side, type, quantity, unitPrice);
        Instant now = clock.instant();
        Order order = Order.create(orderId, command, now).accept(now);

        if (filled > 0) {
            BigDecimal fillPrice = optionalDecimal(node, "cntr_uv").map(BigDecimal::abs)
                    .filter(value -> value.signum() > 0)
                    .or(() -> Optional.ofNullable(unitPrice))
                    .orElseThrow(() -> new BrokerException(
                            "체결 단가를 알 수 없어 주문 " + orderId + " 을(를) 해석하지 못했습니다."));
            order = order.applyExecution(new Execution(
                    orderId + "-AGG", orderId, symbol, side, Math.min(filled, quantity), fillPrice, now));
        }

        if (isCancelled(node) && !order.isTerminal()) {
            order = order.cancel(now);
        }
        return order;
    }

    /** {@code io_tp_nm}(현금매수/현금매도) 또는 {@code trde_tp} 로 매매 구분을 읽는다. */
    private OrderSide sideOf(JsonNode node) {
        String label = optionalText(node, "io_tp_nm").orElse("");
        if (label.contains("매도")) return OrderSide.SELL;
        if (label.contains("매수")) return OrderSide.BUY;
        String trade = optionalText(node, "trde_tp").orElse("");
        if (trade.contains("매도")) return OrderSide.SELL;
        if (trade.contains("매수")) return OrderSide.BUY;
        throw new BrokerException("주문의 매매 구분을 해석하지 못했습니다. io_tp_nm=" + label);
    }

    private boolean isCancelled(JsonNode node) {
        return optionalText(node, "mdfy_cncl_tp").map(value -> value.contains("취소")).orElse(false);
    }

    /** 주문 접수 응답에서 주문번호를 읽는다. */
    public String toOrderId(JsonNode root) {
        return optionalText(root, "ord_no").orElseThrow(() -> new BrokerException(
                "주문 응답에 ord_no 항목이 없어 주문 번호를 확인하지 못했습니다."));
    }

    // ------------------------------------------------------------------
    // 공통 읽기 도우미
    // ------------------------------------------------------------------

    private String text(JsonNode parent, String field) {
        return optionalText(parent, field).orElseThrow(() -> missing(field));
    }

    private Optional<String> optionalText(JsonNode parent, String field) {
        if (parent == null) return Optional.empty();
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String value = node.asText();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private BigDecimal decimal(JsonNode parent, String field) {
        return optionalDecimal(parent, field).orElseThrow(() -> missing(field));
    }

    /**
     * 부호와 0-패딩을 걷어 내고 숫자로 읽는다.
     *
     * <p>{@code "+70700"}, {@code "-00000000196888"}, {@code "000000000000003"} 이 모두 들어온다.
     */
    private Optional<BigDecimal> optionalDecimal(JsonNode parent, String field) {
        return optionalText(parent, field).flatMap(raw -> {
            String value = raw.replace(",", "").trim();
            if (value.isEmpty()) return Optional.empty();
            boolean negative = value.startsWith("-");
            if (negative || value.startsWith("+")) {
                value = value.substring(1);
            }
            if (value.isEmpty()) return Optional.empty();
            try {
                BigDecimal parsed = new BigDecimal(value);
                return Optional.of(negative ? parsed.negate() : parsed);
            } catch (NumberFormatException notANumber) {
                throw new BrokerException(
                        "필드 " + field + " 의 숫자 형식을 해석하지 못했습니다. 입력값 " + raw, notANumber);
            }
        });
    }

    private Optional<Long> optionalLong(JsonNode parent, String field) {
        return optionalDecimal(parent, field).map(BigDecimal::longValue);
    }

    private BrokerException missing(String field) {
        return new BrokerException("증권사 응답에 필수 항목 " + field + " 이(가) 없습니다.");
    }
}
