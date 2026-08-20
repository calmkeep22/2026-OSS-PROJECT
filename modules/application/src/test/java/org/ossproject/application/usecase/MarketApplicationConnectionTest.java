package org.ossproject.application.usecase;

import org.junit.jupiter.api.Test;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.ConnectionListener;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.QuoteListener;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 상태 표시줄처럼 종목과 무관하게 연결만 보는 자리를 위한 통로. */
class MarketApplicationConnectionTest {

    private final StubStream stream = new StubStream();
    private final MarketApplicationService market = new MarketApplicationService(
            new StubStocks(), new StubCandles(), stream, Runnable::run);

    /** 등록 직후 현재 상태를 알려야 한다. 다음 변화까지 화면이 낡은 값을 보이면 안 된다. */
    @Test void reportsTheCurrentStateAsSoonAsSomeoneStartsWatching() {
        List<ConnectionState> seen = new ArrayList<>();

        market.observeConnection((state, detail) -> seen.add(state));

        assertEquals(List.of(ConnectionState.DISCONNECTED), seen);
    }

    @Test void keepsReportingLaterChanges() {
        List<ConnectionState> seen = new ArrayList<>();
        market.observeConnection((state, detail) -> seen.add(state));

        stream.moveTo(ConnectionState.CONNECTED, "로그인 완료");

        assertEquals(ConnectionState.CONNECTED, seen.get(seen.size() - 1));
    }

    @Test void stopsReportingOnceTheWatcherIsClosed() {
        List<ConnectionState> seen = new ArrayList<>();
        EventSubscription watch = market.observeConnection((state, detail) -> seen.add(state));
        int before = seen.size();

        watch.close();
        stream.moveTo(ConnectionState.CONNECTED, null);

        assertEquals(before, seen.size());
        assertDoesNotThrow(watch::close, "여러 번 닫아도 안전해야 합니다");
    }

    @Test void countsWhatIsActuallySubscribed() {
        assertEquals(0, market.liveSubscriptionCount());

        stream.subscribe(List.of("005930", "000660"));

        assertEquals(2, market.liveSubscriptionCount());
    }

    // ------------------------------------------------------------------

    private static final class StubStream implements MarketDataStreamPort {
        private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
        private final Set<String> subscribed = new LinkedHashSet<>();
        private ConnectionState state = ConnectionState.DISCONNECTED;

        void moveTo(ConnectionState next, String detail) {
            state = next;
            listeners.forEach(listener -> listener.onConnectionStateChanged(next, detail));
        }

        @Override public void connect() { moveTo(ConnectionState.CONNECTING, null); }
        @Override public void subscribe(Collection<String> symbols) { subscribed.addAll(symbols); }
        @Override public void unsubscribe(Collection<String> symbols) { subscribed.removeAll(symbols); }
        @Override public Set<String> subscriptions() { return Set.copyOf(subscribed); }
        @Override public void addQuoteListener(QuoteListener listener) { }
        @Override public void removeQuoteListener(QuoteListener listener) { }
        @Override public void addConnectionListener(ConnectionListener listener) { listeners.add(listener); }
        @Override public void removeConnectionListener(ConnectionListener listener) { listeners.remove(listener); }
        @Override public ConnectionState connectionState() { return state; }
        @Override public void close() { state = ConnectionState.DISCONNECTED; listeners.clear(); }
    }

    private static final class StubStocks implements StockQueryPort {
        @Override public List<SecuritySummary> search(String query, int limit) { return List.of(); }
        @Override public StockDetail getDetail(String symbol) { throw new UnsupportedOperationException(); }
    }

    private static final class StubCandles implements CandleQueryPort {
        @Override public List<Candle> getCandles(String symbol, CandleInterval interval, int count) {
            return List.of();
        }
    }
}
