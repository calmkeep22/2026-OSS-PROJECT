package org.ossproject.application.port;

import org.ossproject.finance.model.market.Trade;

/**
 * 체결 한 건이 들어왔을 때 받는다.
 *
 * <p>{@link QuoteListener} 는 현재가 갱신을 알린다. 이쪽은 체결 자체를 알린다. 같은 시세
 * 갱신이라도 "가격이 얼마가 되었다" 와 "몇 주가 어느 방향으로 거래되었다" 는 다른 정보다.
 */
@FunctionalInterface
public interface TradeListener {
    void onTrade(Trade trade);
}
