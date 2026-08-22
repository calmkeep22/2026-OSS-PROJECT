package org.ossproject.application.port;

import org.ossproject.finance.model.market.Quote;

/** 화면과 Sonification에 전달할 정제된 시장 이벤트 수신자. */
public interface MarketApplicationListener {
    void onQuote(Quote quote);

    void onConnectionChanged(ConnectionState state, String safeDetail);
}
