package org.ossproject.application.port;

import org.ossproject.finance.model.SecuritySummary;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.StockDetail;

import java.util.List;

/** Queries securities and the latest display-ready detail for one of them. */
public interface StockQueryPort {
    /**
     * Returns at most {@code limit} securities matching a symbol or name fragment.
     * A blank query returns the available securities so the screen can show a starting list.
     *
     * @param query symbol or name fragment
     * @param limit maximum number of results; must be positive
     */
    List<SecuritySummary> search(String query, int limit);

    /**
     * Returns the latest detail for one security.
     *
     * @param symbol security code obtained from {@link #search(String, int)}
     * @throws IllegalArgumentException when the security is unknown to this adapter
     */
    StockDetail getDetail(String symbol);

    /**
     * 거래소를 포함한 목표 계약. 기존 문자열 어댑터는 기본 구현으로 호환하며,
     * KRX/NXT를 구분하는 어댑터는 이 메서드를 재정의한다.
     */
    default StockDetail getDetail(SecurityId security) {
        if (security == null) throw new IllegalArgumentException("종목 식별자는 필수입니다.");
        return getDetail(security.symbol());
    }
}
