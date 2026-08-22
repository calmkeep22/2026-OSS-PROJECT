package org.ossproject.kiwoom.query;

import org.ossproject.kiwoom.config.KiwoomRestClient;

import org.ossproject.application.port.TradeQueryPort;
import org.ossproject.finance.model.market.Trade;

import java.util.List;
import java.util.Objects;

/** 키움 체결정보 조회를 애플리케이션 포트에 연결한다. */
public final class KiwoomTradeQueryAdapter implements TradeQueryPort {

    private final KiwoomRestClient client;

    public KiwoomTradeQueryAdapter(KiwoomRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<Trade> getRecentTrades(String symbol) {
        return client.fetchRecentTrades(symbol);
    }
}
