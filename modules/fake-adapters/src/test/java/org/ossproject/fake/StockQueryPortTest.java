package org.ossproject.fake;

import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.CandleInterval;
import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.StockDetail;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockQueryPortTest {

    private final FakeStockQueryAdapter stocks = new FakeStockQueryAdapter();
    private final FakeCandleQueryAdapter candles = new FakeCandleQueryAdapter();

    @Test
    void providesAccessibleOhlcvDataForThePriceChart() {
        StockDetail detail = stocks.getDetail("005930");

        assertEquals("삼성전자", detail.name());
        assertEquals(new BigDecimal("73500"), detail.currentPrice());
        assertTrue(detail.low().compareTo(detail.high()) <= 0);
    }

    @Test
    void returnsTheRequestedSecurityRatherThanAFixedOne() {
        assertEquals("Apple", stocks.getDetail("AAPL").name());
        assertEquals("NVIDIA", stocks.getDetail("NVDA").name());
        assertEquals("NAVER", stocks.getDetail("035420").name());
    }

    @Test
    void rejectsAnUnknownSecurity() {
        assertThrows(IllegalArgumentException.class, () -> stocks.getDetail("999999"));
        assertThrows(IllegalArgumentException.class, () -> candles.getCandles("999999", CandleInterval.DAY, 5));
    }

    @Test
    void searchMatchesSymbolAndName() {
        assertEquals(1, stocks.search("005930", 10).size());
        assertEquals("삼성전자", stocks.search("삼성", 10).get(0).name());
        assertEquals("NVIDIA", stocks.search("nvda", 10).get(0).name());
        assertTrue(stocks.search("존재하지않는종목", 10).isEmpty());
    }

    @Test
    void blankSearchListsTheAvailableSecuritiesWithinTheLimit() {
        List<SecuritySummary> all = stocks.search("", 10);
        assertFalse(all.isEmpty());
        assertEquals(2, stocks.search("", 2).size());
    }

    @Test
    void searchCarriesExchangeAndCurrencySoScreensNeedNotGuess() {
        SecuritySummary apple = stocks.search("AAPL", 1).get(0);

        assertEquals("NASDAQ", apple.exchange());
        assertEquals("USD", apple.currency());
        assertFalse(apple.isKrw());
        assertTrue(stocks.search("005930", 1).get(0).isKrw());
    }

    @Test
    void lastCandleClosesAtTheQuotedPrice() {
        for (String symbol : List.of("005930", "AAPL", "NVDA", "035420", "069500", "000660")) {
            List<Candle> series = candles.getCandles(symbol, CandleInterval.DAY, 30);
            assertEquals(stocks.getDetail(symbol).currentPrice(), series.get(series.size() - 1).close(),
                    symbol + " 의 마지막 종가가 현재가와 다릅니다.");
        }
    }

    @Test
    void candlesAreOrderedOldestFirst() {
        List<Candle> series = candles.getCandles("005930", CandleInterval.DAY, 10);

        for (int index = 1; index < series.size(); index++) {
            assertTrue(series.get(index - 1).timestamp().isBefore(series.get(index).timestamp()));
        }
    }
}
