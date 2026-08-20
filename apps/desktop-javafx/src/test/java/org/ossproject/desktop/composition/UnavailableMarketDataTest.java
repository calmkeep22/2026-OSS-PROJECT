package org.ossproject.desktop.composition;

import org.junit.jupiter.api.Test;
import org.ossproject.application.contract.MarketDataStreamPortContract;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.MarketDataStreamPort;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UnavailableMarketDataTest extends MarketDataStreamPortContract {

    private static final String REASON = "키움 API 연결이 필요합니다.";

    @Override
    protected MarketDataStreamPort createStream() {
        return new UnavailableMarketData(REASON);
    }

    /**
     * 조용히 아무것도 보내지 않으면 시세가 멈춘 것인지 연결이 안 된 것인지 알 수 없다.
     * 화면을 볼 수 없는 사용자에게는 특히 구분이 불가능하다.
     */
    @Test void announcesTheFailureWithItsReasonInsteadOfStayingSilent() {
        UnavailableMarketData stream = new UnavailableMarketData(REASON);
        AtomicReference<ConnectionState> seenState = new AtomicReference<>();
        AtomicReference<String> seenDetail = new AtomicReference<>();
        stream.addConnectionListener((state, detail) -> {
            seenState.set(state);
            seenDetail.set(detail);
        });

        stream.connect();

        assertEquals(ConnectionState.FAILED, seenState.get());
        assertEquals(REASON, seenDetail.get());
    }

    /** 시세를 못 받는 것과 화면을 열지 못하는 것은 다른 문제다. */
    @Test void lettingTheDetailScreenOpenMattersMoreThanFailingLoudlyOnSubscribe() {
        MarketDataStreamPort stream = createStream();

        assertDoesNotThrow(() -> stream.subscribe(List.of("005930")));
        assertDoesNotThrow(() -> stream.unsubscribe(List.of("005930")));
    }

    @Test void everyQueryStillFailsWithTheSameReason() {
        UnavailableMarketData none = new UnavailableMarketData(REASON);

        assertEquals(REASON,
                assertThrows(IllegalStateException.class, () -> none.getAccount()).getMessage());
        assertEquals(REASON,
                assertThrows(IllegalStateException.class, () -> none.getDetail("005930")).getMessage());
    }
}
