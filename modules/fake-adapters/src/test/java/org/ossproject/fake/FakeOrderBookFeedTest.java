package org.ossproject.fake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.OrderBook;
import org.ossproject.finance.model.PriceLadder;
import org.ossproject.finance.model.PriceLadderConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeOrderBookFeedTest {

    private FakeOrderBookFeed feed() {
        return new FakeOrderBookFeed("005930", new BigDecimal("73500"), new BigDecimal("100"), 42L);
    }

    @Test
    @DisplayName("10단계 호가창을 만든다")
    void producesTenLevels() {
        OrderBook book = feed().getOrderBook("005930");

        assertEquals(10, book.depth());
        assertTrue(book.bestAsk().isPresent());
        assertTrue(book.bestBid().isPresent());
    }

    @Test
    @DisplayName("매도가 매수보다 항상 높다")
    void keepsAskAboveBid() {
        FakeOrderBookFeed feed = feed();

        for (int i = 0; i < 50; i++) {
            OrderBook book = feed.tick();
            assertTrue(book.bestAsk().orElseThrow().compareTo(book.bestBid().orElseThrow()) > 0);
        }
    }

    @Test
    @DisplayName("리스너에게 갱신을 알린다")
    void notifiesListeners() {
        FakeOrderBookFeed feed = feed();
        List<OrderBook> received = new ArrayList<>();
        feed.addListener(received::add);

        feed.tick();
        feed.tick();

        assertEquals(2, received.size());
    }

    @Test
    @DisplayName("같은 시드는 같은 순서를 재현한다")
    void isReproducible() {
        FakeOrderBookFeed first = feed();
        FakeOrderBookFeed second = feed();

        for (int i = 0; i < 20; i++) {
            assertEquals(first.tick().bestAsk(), second.tick().bestAsk());
        }
    }

    @Test
    @DisplayName("가격 격자와 붙여 실제 화면 흐름을 재현한다")
    void feedsPriceLadder() {
        FakeOrderBookFeed feed = feed();
        PriceLadder ladder = PriceLadder.create(PriceLadderConfig.defaults());
        int recenterCount = 0;

        for (int i = 0; i < 200; i++) {
            OrderBook book = feed.tick();
            PriceLadder.Update update = ladder.update(book, book.midPrice().orElse(null));
            ladder = update.ladder();
            if (update.view().recentered()) {
                recenterCount++;
            }
            assertFalse(update.view().rows().isEmpty());
        }

        // 가격이 조금씩만 움직이므로 재조정은 드물게 일어나야 한다.
        assertTrue(recenterCount < 20, "재조정이 너무 잦다: " + recenterCount);
    }

    @Test
    @DisplayName("잔량 편차가 커서 막대 정규화가 눈에 보인다")
    void hasVariedSizes() {
        OrderBook book = feed().getOrderBook("005930");

        long max = book.maxSize();
        long min = book.levels().stream().mapToLong(level -> level.askSize()).min().orElseThrow();

        assertTrue(max > min * 2, "잔량이 너무 균일하다");
    }
}
