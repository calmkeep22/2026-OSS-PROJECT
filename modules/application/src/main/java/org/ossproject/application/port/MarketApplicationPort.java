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

    /**
     * 실시간 체결로 마지막 봉을 갱신받는다.
     *
     * <p>거래소는 실시간 차트를 보내 주지 않는다. 과거 봉은 {@link #loadCandles} 로 한 번
     * 받고, 그 뒤 마지막 봉은 체결을 모아 직접 갱신해야 한다.
     *
     * @param history 화면이 이미 받아 둔 과거 봉. 마지막 봉을 집계기에 이어 붙인다.
     *                넘기지 않으면 같은 시간대의 봉이 둘로 갈라져 마지막 봉이 두 번 그려진다
     * @param listener 갱신 수신자. 구현이 정한 이벤트 실행자에서 호출된다
     */
    EventSubscription monitorCandles(SecurityId security, CandleInterval interval,
                                     List<Candle> history, CandleListener listener);

    /** 앱 종료 시 실시간 연결과 남은 구독을 정리한다. */
    @Override
    void close();
}
