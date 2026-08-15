package org.ossproject.fake;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockQueryPortTest {
    @Test
    void providesAccessibleOhlcvDataForThePriceChart() {
        var adapter = new FakeStockQueryAdapter();
        var detail = adapter.getDetail("005930");
        assertEquals("삼성전자", detail.name());
        assertEquals(new BigDecimal("73500"), detail.currentPrice());
    }
}
