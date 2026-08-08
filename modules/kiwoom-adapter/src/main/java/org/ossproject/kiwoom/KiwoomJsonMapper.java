package org.ossproject.kiwoom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.SensitiveDataMasker;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 키움 응답 JSON을 도메인 객체로 옮긴다.
 *
 * <p>필드 이름은 {@link KiwoomFieldMap} 에서 가져오므로 이 클래스는 특정 스펙에 묶이지 않는다.
 * 필요한 필드가 없거나 형식이 다르면 조용히 넘어가지 않고 {@link BrokerException} 을 던진다.
 * 금액이 잘못 파싱되어 엉뚱한 주문이 나가는 것보다 즉시 실패하는 편이 안전하다.
 */
public final class KiwoomJsonMapper {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    private final ObjectMapper objectMapper;
    private final KiwoomProperties properties;
    private final Clock clock;

    public KiwoomJsonMapper(ObjectMapper objectMapper, KiwoomProperties properties, Clock clock) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("ObjectMapper 는 필수입니다.");
        }
        if (properties == null) {
            throw new IllegalArgumentException("설정은 필수입니다.");
        }
        if (clock == null) {
            throw new IllegalArgumentException("시계는 필수입니다.");
        }
        this.objectMapper = objectMapper;
        this.properties = properties;
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
    // 시세
    // ------------------------------------------------------------------

    public Quote toQuote(JsonNode node) {
        String symbol = text(node, KiwoomField.QUOTE_SYMBOL);
        return new Quote(
                symbol,
                decimal(node, KiwoomField.QUOTE_PRICE),
                optionalDecimal(node, KiwoomField.QUOTE_PREVIOUS_CLOSE).orElse(null),
                optionalDecimal(node, KiwoomField.QUOTE_BID_PRICE).orElse(null),
                optionalDecimal(node, KiwoomField.QUOTE_ASK_PRICE).orElse(null),
                optionalLong(node, KiwoomField.QUOTE_BID_SIZE).orElse(0L),
                optionalLong(node, KiwoomField.QUOTE_ASK_SIZE).orElse(0L),
                optionalLong(node, KiwoomField.QUOTE_VOLUME).orElse(0L),
                clock.instant());
    }

    // ------------------------------------------------------------------
    // 봉
    // ------------------------------------------------------------------

    public List<Candle> toCandles(JsonNode root, CandleInterval interval) {
        JsonNode list = arrayNode(root, KiwoomField.CANDLE_LIST);
        List<Candle> candles = new ArrayList<>(list.size());
        for (JsonNode node : list) {
            candles.add(toCandle(node, interval));
        }
        // 증권사는 최신순으로 주는 경우가 많다. 도메인 계약은 오래된 것부터다.
        candles.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));
        return List.copyOf(candles);
    }

    public Candle toCandle(JsonNode node, CandleInterval interval) {
        return new Candle(
                parseTimestamp(node, interval),
                interval,
                decimal(node, KiwoomField.CANDLE_OPEN),
                decimal(node, KiwoomField.CANDLE_HIGH),
                decimal(node, KiwoomField.CANDLE_LOW),
                decimal(node, KiwoomField.CANDLE_CLOSE),
                optionalLong(node, KiwoomField.CANDLE_VOLUME).orElse(0L));
    }

    /** {@code yyyyMMdd} 또는 {@code yyyy-MM-dd} 형식의 날짜와 선택적 시각을 조합한다. */
    private Instant parseTimestamp(JsonNode node, CandleInterval interval) {
        String rawDate = text(node, KiwoomField.CANDLE_DATE);
        LocalDate date = parseDate(rawDate);
        LocalTime time = LocalTime.MIDNIGHT;
        if (interval.isIntraday() && properties.fields().has(KiwoomField.CANDLE_TIME)) {
            time = optionalText(node, KiwoomField.CANDLE_TIME)
                    .map(KiwoomJsonMapper::parseTime)
                    .orElse(LocalTime.MIDNIGHT);
        }
        return date.atTime(time).atZone(MARKET_ZONE).toInstant();
    }

    private static LocalDate parseDate(String raw) {
        String value = raw.trim();
        try {
            if (value.matches("\\d{8}")) {
                return LocalDate.of(Integer.parseInt(value.substring(0, 4)),
                        Integer.parseInt(value.substring(4, 6)),
                        Integer.parseInt(value.substring(6, 8)));
            }
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new BrokerException("봉 날짜 형식을 해석하지 못했습니다. 입력값 " + value, e);
        }
    }

    private static LocalTime parseTime(String raw) {
        String value = raw.trim();
        try {
            if (value.matches("\\d{6}")) {
                return LocalTime.of(Integer.parseInt(value.substring(0, 2)),
                        Integer.parseInt(value.substring(2, 4)),
                        Integer.parseInt(value.substring(4, 6)));
            }
            if (value.matches("\\d{4}")) {
                return LocalTime.of(Integer.parseInt(value.substring(0, 2)),
                        Integer.parseInt(value.substring(2, 4)));
            }
            return LocalTime.parse(value);
        } catch (RuntimeException e) {
            throw new BrokerException("봉 시각 형식을 해석하지 못했습니다. 입력값 " + value, e);
        }
    }

    // ------------------------------------------------------------------
    // 계좌
    // ------------------------------------------------------------------

    public Account toAccount(String accountNo, JsonNode root) {
        Balance balance = Balance.of(decimal(root, KiwoomField.ACCOUNT_CASH));
        List<Position> positions = new ArrayList<>();
        for (JsonNode node : arrayNode(root, KiwoomField.ACCOUNT_POSITIONS)) {
            long quantity = longValue(node, KiwoomField.POSITION_QUANTITY);
            if (quantity <= 0) {
                continue;
            }
            BigDecimal averagePrice = decimal(node, KiwoomField.POSITION_AVERAGE_PRICE);
            BigDecimal currentPrice = optionalDecimal(node, KiwoomField.POSITION_CURRENT_PRICE)
                    .orElse(averagePrice);
            positions.add(new Position(
                    text(node, KiwoomField.POSITION_SYMBOL),
                    text(node, KiwoomField.POSITION_NAME),
                    quantity,
                    0L,
                    averagePrice,
                    currentPrice));
        }
        return new Account(accountNo, balance, positions);
    }

    // ------------------------------------------------------------------
    // 주문
    // ------------------------------------------------------------------

    public List<Order> toOrders(JsonNode root) {
        List<Order> orders = new ArrayList<>();
        for (JsonNode node : arrayNode(root, KiwoomField.ORDER_LIST)) {
            orders.add(toOrder(node));
        }
        return List.copyOf(orders);
    }

    /**
     * 증권사 주문 한 건을 도메인 주문으로 옮긴다.
     *
     * <p>증권사는 체결 목록을 따로 주므로, 여기서는 체결 수량과 평균 단가를 합성 체결
     * 한 건으로 표현한다. 체결 이력 자체는 실시간 스트림에서 채운다.
     */
    public Order toOrder(JsonNode node) {
        String orderId = text(node, KiwoomField.ORDER_ID);
        OrderSide side = parseSide(text(node, KiwoomField.ORDER_SIDE));
        OrderType type = parseType(optionalText(node, KiwoomField.ORDER_TYPE).orElse(null));
        long quantity = longValue(node, KiwoomField.ORDER_QUANTITY);
        BigDecimal limitPrice = type == OrderType.LIMIT
                ? decimal(node, KiwoomField.ORDER_PRICE)
                : null;

        OrderCommand command = new OrderCommand(
                text(node, KiwoomField.ORDER_SYMBOL),
                optionalText(node, KiwoomField.ORDER_NAME).orElse(text(node, KiwoomField.ORDER_SYMBOL)),
                side, type, quantity, limitPrice);

        Instant now = clock.instant();
        Order order = Order.create(orderId, command, now);

        String rawStatus = optionalText(node, KiwoomField.ORDER_STATUS).orElse(null);
        OrderStatus status = properties.statusOf(rawStatus);
        if (status == null) {
            throw new BrokerException("알 수 없는 주문 상태 코드입니다. 입력값 " + rawStatus
                    + ". KiwoomProperties 의 상태 코드 대응표를 확인해 주세요.");
        }

        if (status == OrderStatus.REJECTED) {
            return order.reject("증권사가 주문을 거부했습니다.", now);
        }

        order = order.accept(now);

        long filledQuantity = optionalLong(node, KiwoomField.ORDER_FILLED_QUANTITY).orElse(0L);
        if (filledQuantity > 0) {
            BigDecimal fillPrice = optionalDecimal(node, KiwoomField.ORDER_AVERAGE_FILLED_PRICE)
                    .or(() -> Optional.ofNullable(limitPrice))
                    .orElseThrow(() -> new BrokerException(
                            "체결 단가를 알 수 없어 주문 " + orderId + " 을(를) 해석하지 못했습니다."));
            order = order.applyExecution(new Execution(
                    orderId + "-AGG", orderId, command.symbol(), side, filledQuantity, fillPrice, now));
        }

        if (status == OrderStatus.CANCELLED && !order.isTerminal()) {
            order = order.cancel(now);
        }
        return order;
    }

    private OrderSide parseSide(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (var entry : properties.orderSideCodes().entrySet()) {
            if (entry.getValue().toLowerCase(Locale.ROOT).equals(value)) {
                return entry.getKey();
            }
        }
        throw new BrokerException("알 수 없는 매매 구분 코드입니다. 입력값 " + raw);
    }

    private OrderType parseType(String raw) {
        if (raw == null) {
            return OrderType.LIMIT;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (var entry : properties.orderTypeCodes().entrySet()) {
            if (entry.getValue().toLowerCase(Locale.ROOT).equals(value)) {
                return entry.getKey();
            }
        }
        throw new BrokerException("알 수 없는 주문 유형 코드입니다. 입력값 " + raw);
    }

    // ------------------------------------------------------------------
    // 공통 읽기 도우미
    // ------------------------------------------------------------------

    private JsonNode arrayNode(JsonNode parent, KiwoomField field) {
        String name = properties.fields().nameOf(field);
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            return objectMapper.createArrayNode();
        }
        if (!node.isArray()) {
            throw new BrokerException("필드 " + name + " 는 배열이어야 합니다.");
        }
        return node;
    }

    private String text(JsonNode parent, KiwoomField field) {
        return optionalText(parent, field).orElseThrow(() -> missing(field));
    }

    private Optional<String> optionalText(JsonNode parent, KiwoomField field) {
        String name = properties.fields().nameOf(field);
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String value = node.asText();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private BigDecimal decimal(JsonNode parent, KiwoomField field) {
        return optionalDecimal(parent, field).orElseThrow(() -> missing(field));
    }

    private Optional<BigDecimal> optionalDecimal(JsonNode parent, KiwoomField field) {
        return optionalText(parent, field).map(value -> {
            try {
                // 증권사는 부호와 천 단위 구분자를 붙여 보내기도 한다.
                return new BigDecimal(value.replace(",", "").replace("+", "").trim());
            } catch (NumberFormatException e) {
                throw new BrokerException("필드 " + properties.fields().nameOf(field)
                        + " 의 숫자 형식을 해석하지 못했습니다. 입력값 " + value, e);
            }
        });
    }

    private long longValue(JsonNode parent, KiwoomField field) {
        return optionalLong(parent, field).orElseThrow(() -> missing(field));
    }

    private Optional<Long> optionalLong(JsonNode parent, KiwoomField field) {
        return optionalDecimal(parent, field).map(BigDecimal::longValue);
    }

    private BrokerException missing(KiwoomField field) {
        return new BrokerException("증권사 응답에 필수 항목 "
                + properties.fields().nameOf(field) + " 이(가) 없습니다.");
    }
}
