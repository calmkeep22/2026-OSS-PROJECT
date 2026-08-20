package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.Exchange;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TradeTapeViewModelTest {

    private static final SecurityId SAMSUNG = new SecurityId("005930", Exchange.KRX);
    private static final SecurityId HYNIX = new SecurityId("000660", Exchange.KRX);

    private final FakeMarketDataStreamAdapter stream = new FakeMarketDataStreamAdapter();
    private final List<Trade> snapshot = new ArrayList<>();
    private final MarketApplicationService market = new MarketApplicationService(
            new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(), null,
            symbol -> List.copyOf(snapshot), stream,
            Runnable::run, Runnable::run, java.time.Clock.systemUTC());
    private final List<List<Trade>> pushes = new ArrayList<>();
    private final AtomicInteger heldCount = new AtomicInteger(-1);

    private static Trade trade(String symbol, long quantity, int second) {
        return new Trade(symbol, new BigDecimal("73500"), quantity, OrderSide.BUY,
                Instant.parse("2026-08-20T05:00:00Z").plusSeconds(second));
    }

    private TradeTapeViewModel started(int capacity) {
        TradeTapeViewModel model = new TradeTapeViewModel(market, Runnable::run, capacity);
        model.start(SAMSUNG, pushes::add, heldCount::set);
        return model;
    }

    /** 체결 목록은 최근 것부터 읽는다. */
    @Test void keepsTheNewestTradeFirst() {
        TradeTapeViewModel model = started(10);

        stream.emitTrade(trade("005930", 10L, 1));
        stream.emitTrade(trade("005930", 20L, 2));

        assertEquals(20L, model.trades().get(0).quantity());
        assertEquals(10L, model.trades().get(1).quantity());
    }

    /** 무한히 쌓으면 메모리와 화면이 감당하지 못한다. */
    @Test void dropsTheOldestOnceItIsFull() {
        TradeTapeViewModel model = started(2);

        stream.emitTrade(trade("005930", 10L, 1));
        stream.emitTrade(trade("005930", 20L, 2));
        stream.emitTrade(trade("005930", 30L, 3));

        assertEquals(2, model.trades().size());
        assertEquals(30L, model.trades().get(0).quantity());
        assertEquals(20L, model.trades().get(1).quantity());
    }

    /** 읽는 중에 목록이 밀리면 읽던 행이 사라진다. */
    @Test void holdsUpdatesWhileTheTableHasFocus() {
        TradeTapeViewModel model = started(10);
        stream.emitTrade(trade("005930", 10L, 1));
        int before = model.trades().size();

        model.setPaused(true);
        stream.emitTrade(trade("005930", 20L, 2));
        stream.emitTrade(trade("005930", 30L, 3));

        assertEquals(before, model.trades().size(), "멈춘 동안 목록이 바뀌면 안 됩니다");
        assertEquals(2, model.heldCount());
        assertEquals(2, heldCount.get(), "밀린 건수를 화면에 알려야 합니다");
    }

    /** 멈춘 동안 들어온 체결은 버리지 않는다. */
    @Test void releasesHeldTradesInOrderWhenFocusLeaves() {
        TradeTapeViewModel model = started(10);
        model.setPaused(true);
        stream.emitTrade(trade("005930", 20L, 2));
        stream.emitTrade(trade("005930", 30L, 3));

        model.setPaused(false);

        assertEquals(0, model.heldCount());
        assertEquals(30L, model.trades().get(0).quantity(), "가장 최근 체결이 앞에 와야 합니다");
        assertEquals(20L, model.trades().get(1).quantity());
        assertEquals(0, heldCount.get());
    }

    @Test void ignoresTradesForAnotherSymbol() {
        TradeTapeViewModel model = started(10);

        stream.emitTrade(trade("000660", 10L, 1));

        assertTrue(model.trades().isEmpty());
    }

    @Test void clearsTheTapeWhenTheStockChanges() {
        TradeTapeViewModel model = started(10);
        stream.emitTrade(trade("005930", 10L, 1));

        model.start(HYNIX, pushes::add, heldCount::set);

        assertTrue(model.trades().isEmpty(), "이전 종목의 체결이 남으면 안 됩니다");
    }

    @Test void stoppingReleasesTheSubscription() {
        TradeTapeViewModel model = started(10);
        assertTrue(stream.subscriptions().contains("005930"));

        model.stop();

        assertFalse(stream.subscriptions().contains("005930"));
        assertDoesNotThrow(model::stop);
    }

    @Test void restartingDoesNotStackSubscriptions() {
        TradeTapeViewModel model = started(10);
        model.start(SAMSUNG, pushes::add, heldCount::set);
        pushes.clear();

        stream.emitTrade(trade("005930", 10L, 1));

        assertEquals(1, pushes.size(), "구독이 하나만 살아 있어야 합니다");
    }

    /**
     * 실시간만 붙이면 다음 체결이 올 때까지 목록이 비어 있다. 장 시간 외에는 영영 오지
     * 않으므로 화면을 열 때 최근 내역을 받아 둔다.
     */
    @Test void showsQueriedTradesBeforeAnyRealtimeOneArrives() {
        snapshot.add(trade("005930", 30L, 3));
        snapshot.add(trade("005930", 20L, 2));
        snapshot.add(trade("005930", 10L, 1));

        TradeTapeViewModel model = started(10);

        assertEquals(3, model.trades().size());
        assertEquals(30L, model.trades().get(0).quantity(), "조회 결과도 최신이 앞이어야 합니다");
        assertEquals(10L, model.trades().get(2).quantity());
    }

    /** 조회한 뒤 들어온 실시간 체결이 앞에 붙어야 한다. */
    @Test void putsRealtimeTradesAheadOfTheQueriedOnes() {
        snapshot.add(trade("005930", 20L, 2));
        TradeTapeViewModel model = started(10);

        stream.emitTrade(trade("005930", 99L, 9));

        assertEquals(99L, model.trades().get(0).quantity());
        assertEquals(20L, model.trades().get(1).quantity());
    }

    /** 조회가 실패해도 구독은 살려 둔다. 지금 못 받는 것과 앞으로도 못 받는 것은 다르다. */
    @Test void keepsTheSubscriptionWhenTheSnapshotQueryFails() {
        MarketApplicationService failing = new MarketApplicationService(
                new FakeStockQueryAdapter(), new FakeCandleQueryAdapter(), null,
                symbol -> { throw new IllegalStateException("조회 실패"); }, stream,
                Runnable::run, Runnable::run, java.time.Clock.systemUTC());
        TradeTapeViewModel model = new TradeTapeViewModel(failing, Runnable::run, 10);

        assertDoesNotThrow(() -> model.start(SAMSUNG, pushes::add, heldCount::set));
        stream.emitTrade(trade("005930", 10L, 1));

        assertEquals(1, model.trades().size(), "이후 실시간 체결은 받아야 합니다");
    }
}
