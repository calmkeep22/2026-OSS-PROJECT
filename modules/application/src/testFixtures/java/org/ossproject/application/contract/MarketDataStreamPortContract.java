package org.ossproject.application.contract;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Reusable lifecycle contract for fake and live market-data stream adapters. */
public abstract class MarketDataStreamPortContract {
    protected abstract MarketDataStreamPort createStream();

    @Test
    void followsConnectionAndSubscriptionContract() {
        MarketDataStreamPort stream = createStream();
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());

        stream.subscribe(List.of("005930", "000660", "005930"));
        assertEquals(Set.of("005930", "000660"), stream.subscriptions());

        // connect() 는 비동기다. 실제 증권사 스트림은 소켓이 열린 뒤에도 인증 핸드셰이크를
        // 거쳐야 쓸 수 있으므로, 호출 직후 CONNECTED 를 보장할 수 없다. 여기서는
        // "더 이상 끊긴 상태가 아니다" 까지만 계약으로 삼는다.
        stream.connect();
        assertNotEquals(ConnectionState.DISCONNECTED, stream.connectionState());

        stream.unsubscribe(List.of("000660"));
        assertEquals(Set.of("005930"), stream.subscriptions());

        stream.close();
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());
    }
}
