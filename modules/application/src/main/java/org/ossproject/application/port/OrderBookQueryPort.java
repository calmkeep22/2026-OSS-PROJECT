package org.ossproject.application.port;

import org.ossproject.finance.model.OrderBook;

/**
 * 호가창 단건 조회 포트.
 *
 * <p>화면을 처음 열 때 한 장을 받아 두고, 이후 갱신은
 * {@link MarketDataStreamPort} 의 실시간 구독으로 처리한다. 실시간으로 받을 수 있는 것을
 * 반복 조회하면 증권사 호출 한도를 금방 소진한다.
 */
public interface OrderBookQueryPort {

    OrderBook getOrderBook(String symbol);
}
