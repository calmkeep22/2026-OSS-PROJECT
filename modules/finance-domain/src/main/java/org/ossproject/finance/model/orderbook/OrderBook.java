package org.ossproject.finance.model.orderbook;

import org.ossproject.finance.model.market.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 호가창 한 장. 거래소가 공개하는 10단계 호가를 담는다.
 *
 * <p>{@link Quote} 는 체결가 한 점이고, 이 타입은 아직 체결되지 않은 주문이 가격대별로
 * 얼마나 쌓여 있는지를 보여 준다.
 *
 * <p>단계 수를 고정하지 않는다. 증권사나 시장 상황에 따라 5단계만 올 수도 있고, 장 전
 * 시간에는 일부 단계가 비어 있을 수도 있기 때문이다.
 */
public record OrderBook(
        String symbol,
        List<OrderBookLevel> levels,
        long totalAskSize,
        long totalBidSize,
        Instant timestamp
) {
    public OrderBook {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("시각은 필수입니다.");
        }
        if (totalAskSize < 0 || totalBidSize < 0) {
            throw new IllegalArgumentException("총잔량은 0 이상이어야 합니다.");
        }
        List<OrderBookLevel> sorted = new ArrayList<>(levels == null ? List.of() : levels);
        sorted.sort(Comparator.comparingInt(OrderBookLevel::level));
        levels = List.copyOf(sorted);
    }

    /** 총잔량을 단계별 잔량 합계로 계산해 만든다. */
    public static OrderBook of(String symbol, List<OrderBookLevel> levels, Instant timestamp) {
        long asks = levels == null ? 0L : levels.stream().mapToLong(OrderBookLevel::askSize).sum();
        long bids = levels == null ? 0L : levels.stream().mapToLong(OrderBookLevel::bidSize).sum();
        return new OrderBook(symbol, levels, asks, bids, timestamp);
    }

    public int depth() {
        return levels.size();
    }

    public boolean isEmpty() {
        return levels.isEmpty();
    }

    public Optional<OrderBookLevel> level(int level) {
        return levels.stream().filter(l -> l.level() == level).findFirst();
    }

    /** 매도 최우선 호가. */
    public Optional<BigDecimal> bestAsk() {
        return levels.stream().filter(OrderBookLevel::hasAsk)
                .min(Comparator.comparing(OrderBookLevel::askPrice))
                .map(OrderBookLevel::askPrice);
    }

    /** 매수 최우선 호가. */
    public Optional<BigDecimal> bestBid() {
        return levels.stream().filter(OrderBookLevel::hasBid)
                .max(Comparator.comparing(OrderBookLevel::bidPrice))
                .map(OrderBookLevel::bidPrice);
    }

    /** 매도·매수 최우선 호가 차이. 한쪽이 없으면 비어 있다. */
    public Optional<BigDecimal> spread() {
        Optional<BigDecimal> ask = bestAsk();
        Optional<BigDecimal> bid = bestBid();
        if (ask.isEmpty() || bid.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ask.get().subtract(bid.get()));
    }

    /** 매도·매수 최우선 호가의 중간값. 가격 격자의 중심을 잡을 때 쓴다. */
    public Optional<BigDecimal> midPrice() {
        Optional<BigDecimal> ask = bestAsk();
        Optional<BigDecimal> bid = bestBid();
        if (ask.isEmpty() || bid.isEmpty()) {
            return ask.or(() -> bid);
        }
        return Optional.of(ask.get().add(bid.get())
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
    }

    /** 화면에 보이는 잔량 중 가장 큰 값. 막대 길이를 정규화할 기준이다. */
    public long maxSize() {
        return levels.stream().mapToLong(OrderBookLevel::maxSize).max().orElse(0L);
    }

    /**
     * 매수 잔량 비율(%). 50보다 크면 매수세가 우세하다.
     *
     * <p>색을 못 보는 사용자에게 "지금 매수 우위인가"를 한 숫자로 전달할 수 있어,
     * 음성 요약에 쓰기 좋다.
     */
    public BigDecimal bidRatioPercent() {
        long total = totalAskSize + totalBidSize;
        if (total == 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(totalBidSize)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /** 음성으로 그대로 읽어 줄 수 있는 한 줄 요약. */
    public String describe() {
        if (isEmpty()) {
            return "호가 정보가 없습니다.";
        }
        StringBuilder sb = new StringBuilder();
        bestBid().ifPresent(bid -> sb.append("매수 최우선 ").append(bid.toPlainString()).append("원, "));
        bestAsk().ifPresent(ask -> sb.append("매도 최우선 ").append(ask.toPlainString()).append("원, "));
        sb.append("매수 비중 ").append(bidRatioPercent()).append("퍼센트입니다.");
        return sb.toString();
    }
}
