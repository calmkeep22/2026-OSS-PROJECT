package org.ossproject.kiwoom;

import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;

import java.util.List;
import java.util.Objects;

/** 키움 차트 조회를 애플리케이션 포트에 연결한다. */
public final class KiwoomCandleQueryAdapter implements CandleQueryPort {

    private final KiwoomRestClient client;

    public KiwoomCandleQueryAdapter(KiwoomRestClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<Candle> getCandles(String symbol, CandleInterval interval, int count) {
        return client.fetchCandles(symbol, interval, count);
    }
}
