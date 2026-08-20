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

    /**
     * 종목과 무관하게 실시간 연결 상태를 관찰한다.
     *
     * <p>{@link #monitor} 는 종목을 하나 붙잡고 있어야 상태를 알려 준다. 상태 표시줄처럼
     * 종목과 상관없이 연결만 보여 주는 자리는 이 통로를 쓴다.
     *
     * <p>등록 직후 현재 상태로 한 번 호출한다. 화면이 만들어진 뒤 다음 변화가 올 때까지
     * 낡은 값을 보여 주지 않게 하기 위해서다.
     */
    EventSubscription observeConnection(ConnectionListener listener);

    /** 지금 실시간으로 구독 중인 종목 수. */
    int liveSubscriptionCount();

    /**
     * 실시간 호가창을 구독한다.
     *
     * <p>공급원이 호가를 주지 않으면 구독은 만들어지되 아무것도 오지 않는다. 예외를 던지면
     * 종목 상세 화면 진입 자체가 막히는데, 호가를 못 받는 것과 화면을 열지 못하는 것은
     * 다른 문제다. 화면은 {@link #supportsOrderBook()} 로 미리 구분한다.
     *
     * @param listener 갱신 수신자. 구현이 정한 이벤트 실행자에서 호출된다
     */
    EventSubscription monitorOrderBook(SecurityId security, OrderBookListener listener);

    /** 공급원이 실시간 호가를 주는지. 거짓이면 화면은 호가창 대신 안내를 보여 준다. */
    boolean supportsOrderBook();

    /**
     * 실시간 체결을 구독한다.
     *
     * <p>{@link #monitor} 가 주는 {@link org.ossproject.finance.model.Quote} 는 현재가와
     * 누적 거래량만 담는다. 체결 목록을 만들려면 건별 수량과 방향이 필요하다.
     */
    EventSubscription monitorTrades(SecurityId security, TradeListener listener);

    /** 공급원이 체결 건별 정보를 주는지. 거짓이면 화면은 체결 목록 대신 안내를 보여 준다. */
    boolean supportsTrades();

    /** 앱 종료 시 실시간 연결과 남은 구독을 정리한다. */
    @Override
    void close();
}
