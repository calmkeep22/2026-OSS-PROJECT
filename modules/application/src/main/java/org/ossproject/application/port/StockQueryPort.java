package org.ossproject.application.port;

import org.ossproject.finance.model.StockDetail;

/** Queries the latest display-ready detail for a security. */
public interface StockQueryPort {
    StockDetail getDetail(String symbol);
}
