package org.ossproject.application.contract;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Reusable lifecycle contract for fake and live market-data stream adapters. */
public abstract class MarketDataStreamPortContract {
    protected abstract MarketDataStreamPort createStream();

    @Test
    void followsConnectionAndSubscriptionContract() {
        MarketDataStreamPort stream = createStream();
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());

        stream.subscribe(List.of("005930", "000660", "005930"));
        assertEquals(Set.of("005930", "000660"), stream.subscriptions());

        stream.connect();
        assertEquals(ConnectionState.CONNECTED, stream.connectionState());

        stream.unsubscribe(List.of("000660"));
        assertEquals(Set.of("005930"), stream.subscriptions());

        stream.close();
        assertEquals(ConnectionState.DISCONNECTED, stream.connectionState());
    }
}
