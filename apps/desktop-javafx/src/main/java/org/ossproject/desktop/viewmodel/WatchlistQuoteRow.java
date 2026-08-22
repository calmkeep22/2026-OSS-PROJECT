package org.ossproject.desktop.viewmodel;

import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.finance.model.market.StockDetail;

import java.math.RoundingMode;
import java.util.Objects;

/** 관심종목 식별 정보와 현재 조회된 시세를 결합한 화면 전용 행. */
public record WatchlistQuoteRow(
        WatchlistItem item,
        String displayPrice,
        String displayChange,
        String displayVolume,
        String quoteStatus
) {
    public WatchlistQuoteRow {
        Objects.requireNonNull(item, "item");
        displayPrice = Objects.requireNonNull(displayPrice, "displayPrice");
        displayChange = Objects.requireNonNull(displayChange, "displayChange");
        displayVolume = Objects.requireNonNull(displayVolume, "displayVolume");
        quoteStatus = Objects.requireNonNull(quoteStatus, "quoteStatus");
    }

    public static WatchlistQuoteRow available(WatchlistItem item, StockDetail detail) {
        Objects.requireNonNull(detail, "detail");
        String price = item.overseas()
                ? "$" + detail.currentPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : String.format("%,d원", detail.currentPrice().setScale(0, RoundingMode.HALF_UP).longValue());
        var rate = detail.changeRate().setScale(2, RoundingMode.HALF_UP);
        String change = (rate.signum() > 0 ? "+" : "") + rate.toPlainString() + "%";
        return new WatchlistQuoteRow(item, price, change, String.format("%,d", detail.volume()), "조회됨");
    }

    public static WatchlistQuoteRow unavailable(WatchlistItem item) {
        return new WatchlistQuoteRow(item, "조회 실패", "-", "-", "연결 확인 필요");
    }

    public String group() { return item.group(); }
    public String symbol() { return item.symbol(); }
    public String securityName() { return item.securityName(); }
    public String alertText() { return item.alertText(); }
    public boolean overseas() { return item.overseas(); }
    public boolean quoteAvailable() { return "조회됨".equals(quoteStatus); }

    public String accessibleDescription() {
        return securityName() + " " + symbol() + ", 현재가 " + displayPrice
                + ", 등락률 " + displayChange + ", 시세 상태 " + quoteStatus;
    }
}
