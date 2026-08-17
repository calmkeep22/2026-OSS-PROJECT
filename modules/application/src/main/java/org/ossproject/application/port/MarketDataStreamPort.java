package org.ossproject.application.port;

import org.ossproject.finance.model.SecurityId;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 실시간 시세 스트림 포트.
 *
 * <p>구현체는 연결이 끊기면 스스로 재연결하고, 재연결에 성공하면 이전에 구독하던 종목을
 * 다시 구독해야 한다. 연결 상태는 {@link ConnectionListener} 로 통지한다.
 */
public interface MarketDataStreamPort extends AutoCloseable {

    /** 연결을 시작한다. 이미 연결되어 있으면 아무 일도 하지 않는다. */
    void connect();

    /** 종목을 구독한다. 연결 전에 호출하면 연결 후 자동으로 반영된다. */
    void subscribe(Collection<String> symbols);

    /** 거래소 포함 식별자를 사용하는 단일 종목 구독 호환 경계. */
    default void subscribe(SecurityId security) {
        if (security == null) throw new IllegalArgumentException("종목 식별자는 필수입니다.");
        subscribe(List.of(security.symbol()));
    }

    void unsubscribe(Collection<String> symbols);

    default void unsubscribe(SecurityId security) {
        if (security == null) return;
        unsubscribe(List.of(security.symbol()));
    }

    /** 현재 구독 중인 종목. */
    Set<String> subscriptions();

    void addQuoteListener(QuoteListener listener);

    void removeQuoteListener(QuoteListener listener);

    void addConnectionListener(ConnectionListener listener);

    void removeConnectionListener(ConnectionListener listener);

    ConnectionState connectionState();

    @Override
    void close();
}
