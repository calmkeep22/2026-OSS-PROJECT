package org.ossproject.application.port;

import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecurityId;

import java.util.List;

/** The single historical price-series query contract. */
public interface CandleQueryPort {
    /** Returns at most {@code count} candles in ascending timestamp order. */
    List<Candle> getCandles(String symbol, CandleInterval interval, int count);

    /** 거래소 포함 식별자를 사용하는 목표 계약의 호환 오버로드. */
    default List<Candle> getCandles(SecurityId security, CandleInterval interval, int count) {
        if (security == null) throw new IllegalArgumentException("종목 식별자는 필수입니다.");
        return getCandles(security.symbol(), interval, count);
    }
}
