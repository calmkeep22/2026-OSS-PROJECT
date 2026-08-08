package org.ossproject.fake;

import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.PricePeriod;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockQueryPortTest {
    @Test
    void providesAccessibleOhlcvDataForThePriceChart() {
        var adapter = new FakeStockQueryAdapter();
        var detail = adapter.getDetail("005930");
        var history = adapter.getPriceHistory("005930", PricePeriod.MONTH);
        assertEquals("삼성전자", detail.name());
        assertEquals(new BigDecimal("73500"), detail.currentPrice());
        assertEquals(9, history.size());
        assertEquals(new BigDecimal("75200"), history.get(8).high());
    }
}
