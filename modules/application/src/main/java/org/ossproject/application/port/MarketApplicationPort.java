package org.ossproject.application.port;

import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** JavaFX가 저수준 증권사 조회·WebSocket 구현을 모르고 시장 정보를 사용하는 경계. */
public interface MarketApplicationPort extends AutoCloseable {
    CompletionStage<List<SecuritySummary>> search(String query, int limit);

    CompletionStage<StockDetail> loadDetail(SecurityId security);

    CompletionStage<List<Candle>> loadCandles(
            SecurityId security, CandleInterval interval, int count);

    EventSubscription monitor(SecurityId security, MarketApplicationListener listener);

    /** 앱 종료 시 실시간 연결과 남은 구독을 정리한다. */
    @Override
    void close();
}
