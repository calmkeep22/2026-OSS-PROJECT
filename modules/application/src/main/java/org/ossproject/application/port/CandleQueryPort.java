package org.ossproject.application.port;

import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;

import java.util.List;

/** The single historical price-series query contract. */
public interface CandleQueryPort {
    /** Returns at most {@code count} candles in ascending timestamp order. */
    List<Candle> getCandles(String symbol, CandleInterval interval, int count);
}
