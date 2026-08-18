package org.ossproject.application.port;

import org.ossproject.finance.model.OrderBook;

/**
 * 실시간 호가창 수신자.
 *
 * <p>호가는 초당 수십 번 갱신될 수 있다. 화면 계층은 받은 즉시 그리지 말고 갱신 주기를
 * 묶어야 한다. 저시력 사용자가 읽을 수 없는 속도로 다시 그리는 것은 정보를 주는 게 아니라
 * 화면을 못 쓰게 만드는 일이다.
 */
@FunctionalInterface
public interface OrderBookListener {

    void onOrderBook(OrderBook orderBook);
}
