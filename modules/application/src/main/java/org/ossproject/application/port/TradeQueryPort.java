package org.ossproject.application.port;

import org.ossproject.finance.model.market.Trade;

import java.util.List;

/**
 * 최근 체결 내역 조회.
 *
 * <p>실시간 구독만으로는 다음 체결이 올 때까지 화면이 비어 있고, 장 시간 외에는 영영 오지
 * 않는다. 화면을 열 때 최근 내역을 한 번 받아 두고 그 뒤로 실시간으로 잇는다.
 */
public interface TradeQueryPort {

    /** 최근 것이 앞에 온다. */
    List<Trade> getRecentTrades(String symbol);
}
