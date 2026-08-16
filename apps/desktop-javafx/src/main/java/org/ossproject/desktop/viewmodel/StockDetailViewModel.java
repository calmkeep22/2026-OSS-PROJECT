package org.ossproject.desktop.viewmodel;

import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.PricePeriod;
import org.ossproject.finance.model.PricePoint;
import org.ossproject.finance.model.StockDetail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 선택 종목의 상세와 차트 데이터를 조회한다.
 *
 * <p>이 클래스는 값을 만들어 내지 않는다. 현재가·시가·고가·저가·거래량은 모두
 * {@link StockQueryPort} 가 준 값을 그대로 쓰고, 차트는 {@link CandleQueryPort} 가 준 봉을
 * 그대로 쓴다. 화면에 보이는 숫자가 조회 결과와 다르면 시각장애인 사용자는 그 사실을
 * 확인할 방법이 없기 때문이다.
 */
public final class StockDetailViewModel {

    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Seoul");

    /** 차트 기간 버튼. 각 버튼은 봉 주기와 조회 개수로만 정의된다. */
    public enum ChartRange {
        MINUTE_1("1분", CandleInterval.MINUTE_1, 60),
        MINUTE_5("5분", CandleInterval.MINUTE_5, 78),
        MINUTE_15("15분", CandleInterval.MINUTE_15, 64),
        MINUTE_60("60분", CandleInterval.MINUTE_60, 48),
        DAY("일", CandleInterval.DAY, 30),
        WEEK("주", CandleInterval.WEEK, 26),
        MONTH("월", CandleInterval.MONTH, 24);

        private final String label;
        private final CandleInterval interval;
        private final int count;

        ChartRange(String label, CandleInterval interval, int count) {
            this.label = label;
            this.interval = interval;
            this.count = count;
        }

        public String label() { return label; }
        public CandleInterval interval() { return interval; }
        public int count() { return count; }
    }

    private final DesktopSession session;
    private final StockQueryPort stockQuery;
    private final CandleQueryPort candleQuery;

    public StockDetailViewModel(DesktopSession session, StockQueryPort stockQuery,
                                CandleQueryPort candleQuery) {
        this.session = Objects.requireNonNull(session, "session");
        this.stockQuery = Objects.requireNonNull(stockQuery, "stockQuery");
        this.candleQuery = Objects.requireNonNull(candleQuery, "candleQuery");
    }

    public StockSelection selection() { return session.selectedStock(); }

    /** 선택 종목의 최신 상세. 조회 결과를 가공하지 않고 그대로 돌려준다. */
    public StockDetail detail() {
        return stockQuery.getDetail(selection().symbol());
    }

    public List<PricePoint> history(ChartRange range) {
        Objects.requireNonNull(range, "range");
        return history(range.interval(), range.count());
    }

    public List<PricePoint> history(PricePeriod period) {
        Objects.requireNonNull(period, "period");
        return switch (period) {
            case DAY -> history(CandleInterval.MINUTE_5, 78);
            case WEEK -> history(CandleInterval.DAY, 5);
            case MONTH -> history(CandleInterval.DAY, 22);
            case THREE_MONTHS -> history(CandleInterval.DAY, 66);
            case YEAR -> history(CandleInterval.WEEK, 52);
        };
    }

    private List<PricePoint> history(CandleInterval interval, int count) {
        return candleQuery.getCandles(selection().symbol(), interval, count).stream()
                .map(candle -> candle.toPricePoint(MARKET_ZONE))
                .toList();
    }

    /** 선택 종목의 통화에 맞춘 금액 표기. */
    public String formatPrice(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        if (selection().overseas()) {
            return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return String.format("%,d원", value.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    /** 주문 화면 입력란에 넣을 단가. 표기 기호 없이 숫자만 돌려준다. */
    public String plainOrderPrice() {
        return detail().currentPrice().stripTrailingZeros().toPlainString();
    }
}
