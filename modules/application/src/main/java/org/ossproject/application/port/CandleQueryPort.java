package org.ossproject.application.port;

import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;

import java.util.List;

/**
 * 봉 조회 포트.
 *
 * <p>기존 {@link StockQueryPort#getPriceHistory} 는 일봉만 돌려준다. 분봉과 실시간
 * 해상도가 필요한 계층은 이 포트를 쓴다.
 */
public interface CandleQueryPort {

    /**
     * 최근 봉을 오래된 것부터 시간순으로 돌려준다.
     *
     * @param count 최대 개수
     */
    List<Candle> getCandles(String symbol, CandleInterval interval, int count);
}
