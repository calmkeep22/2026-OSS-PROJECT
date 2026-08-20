package org.ossproject.kiwoom.stream;

import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.Trade;

/**
 * 실시간 WebSocket 에서 올라온 사건.
 *
 * <p>키움은 로그인 응답, 하트비트, 시세를 모두 같은 소켓으로 보낸다. 어떤 종류인지
 * 판별하는 책임을 프로토콜 계층에 두면, 스트림 계층은 사건별 처리만 하면 된다.
 */
public sealed interface KiwoomStreamEvent {

    /** 로그인 응답. */
    record LoginResult(boolean success, int returnCode, String message) implements KiwoomStreamEvent {
    }

    /**
     * 하트비트. 받은 패킷을 그대로 되돌려보내야 연결이 유지된다.
     *
     * @param echo 서버로 그대로 돌려보낼 원본 메시지
     */
    record Ping(String echo) implements KiwoomStreamEvent {
    }

    /** 실시간 호가창. */
    record OrderBookUpdate(OrderBook orderBook) implements KiwoomStreamEvent {
    }

    /** 실시간 체결. 현재가와 누적 거래량이 갱신된다. */
    record QuoteUpdate(Quote quote) implements KiwoomStreamEvent {
    }

    /** 실시간 체결 한 건. 수량과 방향을 담는다. */
    record TradeUpdate(Trade trade) implements KiwoomStreamEvent {
    }

    /** 구독 등록·해제 응답 등 우리가 따로 처리하지 않는 메시지. */
    record Ignored(String reason) implements KiwoomStreamEvent {
    }
}
