package org.ossproject.application.port;
import org.ossproject.finance.model.PricePeriod;
import org.ossproject.finance.model.PricePoint;
import org.ossproject.finance.model.StockDetail;
import java.util.List;
public interface StockQueryPort {
    StockDetail getDetail(String symbol);
    List<PricePoint> getPriceHistory(String symbol, PricePeriod period);
}
