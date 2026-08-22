package org.ossproject.kiwoom.query;

import org.ossproject.kiwoom.config.KiwoomRestClient;

import org.ossproject.application.port.OrderBookQueryPort;
import org.ossproject.finance.model.OrderBook;

import java.util.Objects;

/**
 * 키움 호가 조회를 애플리케이션 포트에 연결한다.
 *
 * <p>실시간 구독만으로는 다음 호가가 올 때까지 화면이 비어 있고, 장 시간 외에는 영영 오지
 * 않는다. 화면을 열 때 이 조회로 한 장을 받아 두고 그 뒤로 실시간으로 잇는다.
 */
public final class KiwoomOrderBookAdapter implements OrderBookQueryPort {

    private final KiwoomRestClient client;

    public KiwoomOrderBookAdapter(KiwoomRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public OrderBook getOrderBook(String symbol) {
        return client.fetchOrderBook(symbol);
    }
}
