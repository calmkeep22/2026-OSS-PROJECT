package org.ossproject.desktop.viewmodel;

import org.ossproject.finance.model.SecuritySummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 검색 결과 한 건의 화면 표현.
 *
 * <p>표시 문자열은 조회 결과에서 만들고, 다시 숫자로 되돌리지 않는다. 값이 필요한 화면은
 * {@link #summary()} 의 숫자를 쓴다.
 */
public record StockSearchItem(SecuritySummary summary) {

    public StockSearchItem {
        Objects.requireNonNull(summary, "summary");
    }

    public static StockSearchItem of(SecuritySummary summary) {
        return new StockSearchItem(summary);
    }

    public String market() { return summary.market(); }
    public String symbol() { return summary.symbol(); }
    public String name() { return summary.name(); }
    public String exchange() { return summary.exchange(); }

    /** 통화에 맞춘 현재가 표기. */
    public String price() {
        BigDecimal value = summary.currentPrice();
        if (summary.isKrw()) {
            return String.format("%,d원", value.setScale(0, RoundingMode.HALF_UP).longValue());
        }
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 부호를 포함한 등락률 표기. */
    public String changeRate() {
        BigDecimal rate = summary.changeRate().setScale(2, RoundingMode.HALF_UP);
        return (rate.signum() > 0 ? "+" : "") + rate.toPlainString() + "%";
    }

    /** 스크린리더가 한 행을 한 문장으로 읽을 수 있는 설명. */
    public String accessibleDescription() {
        return "%s %s, %s, 현재가 %s, 등락률 %s"
                .formatted(name(), symbol(), exchange(), price(), changeRate());
    }

    public StockSelection toSelection() {
        return StockSelection.from(summary);
    }

    public boolean matchesMarket(String marketFilter) {
        return marketFilter == null || marketFilter.equals("전체") || market().equals(marketFilter);
    }
}
