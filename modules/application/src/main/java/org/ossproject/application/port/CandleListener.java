package org.ossproject.application.port;

import org.ossproject.finance.model.Candle;

/**
 * 실시간 봉 갱신 수신자.
 *
 * <p>화면은 마지막 봉 하나만 다시 그리면 된다. 이미 마감된 봉은 다시 바뀌지 않는다.
 */
public interface CandleListener {

    /**
     * 진행 중인 봉이 갱신됐다.
     *
     * @param candle 갱신된 마지막 봉
     */
    void onCandleUpdated(Candle candle);

    /**
     * 봉 하나가 마감되고 새 봉이 시작됐다.
     *
     * <p>화면은 마감된 봉을 확정해 두고 새 봉을 뒤에 붙인다.
     */
    default void onCandleCompleted(Candle completed) {
        // 마감 시점을 따로 다루지 않는 화면을 위한 기본 동작.
    }
}
