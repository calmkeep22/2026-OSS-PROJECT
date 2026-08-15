package org.ossproject.kiwoom.stream;

import org.ossproject.finance.model.Quote;

import java.util.Collection;
import java.util.Optional;

/**
 * 실시간 스트림의 메시지 형식.
 *
 * <p>구독 요청과 시세 메시지의 형식은 증권사 문서에 따라 다르므로 별도 인터페이스로 뺐다.
 * 재연결·구독 복원 같은 어려운 부분은 {@link KiwoomMarketDataStream} 이 담당하고,
 * 형식만 여기서 갈아 끼운다.
 */
interface StreamProtocol {

    /** 구독 요청 메시지. */
    String subscribeMessage(Collection<String> symbols);

    /** 구독 해제 메시지. */
    String unsubscribeMessage(Collection<String> symbols);

    /**
     * 수신 메시지를 시세로 해석한다.
     *
     * <p>시세가 아닌 메시지(구독 응답, 하트비트 등)는 비어 있는 값을 돌려준다.
     * 예외를 던지면 스트림이 끊기므로, 해석할 수 없는 메시지는 조용히 무시해야 한다.
     */
    Optional<Quote> parseQuote(String message);

    /** 서버가 하트비트를 요구할 때 보낼 메시지. 필요 없으면 비어 있는 값. */
    default Optional<String> heartbeatMessage() {
        return Optional.empty();
    }
}
