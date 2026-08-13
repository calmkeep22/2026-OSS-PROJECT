package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.fake.FakeStockQueryAdapter;

import static org.junit.jupiter.api.Assertions.*;

class StockDetailViewModelTest {
    @Test void selectedOverseasStockControlsDetailAndScaledChart() {
        DesktopSession session = new DesktopSession();
        session.selectStock(new StockSelection("미국", "AAPL", "Apple", "NASDAQ", "$228.40", "+0.83%"));
        StockDetailViewModel viewModel = new StockDetailViewModel(session, new FakeStockQueryAdapter());

        assertEquals("AAPL", viewModel.detail().symbol());
        assertEquals("$228.40", viewModel.formatPrice(viewModel.detail().currentPrice()));
        var history = viewModel.history(StockDetailViewModel.ChartRange.DAY);
        assertEquals(viewModel.detail().currentPrice(), history.get(history.size() - 1).close());
    }
}
