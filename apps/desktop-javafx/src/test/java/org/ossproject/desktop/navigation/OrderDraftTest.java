package org.ossproject.desktop.navigation;

import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderType;

import static org.junit.jupiter.api.Assertions.*;

class OrderDraftTest {
    @Test void keepsTheSideAndOriginSelectedByTheUser() {
        OrderDraft draft = new OrderDraft("005930", "삼성전자", OrderSide.SELL,
                OrderType.MARKET, 12, "73500", Screen.STOCK_DETAIL);

        assertEquals(OrderSide.SELL, draft.side());
        assertEquals(OrderType.MARKET, draft.type());
        assertEquals(12, draft.quantity());
        assertEquals(Screen.STOCK_DETAIL, draft.origin());
    }

    @Test void rejectsMissingSecurityIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderDraft(" ", "삼성전자", OrderSide.BUY,
                        OrderType.LIMIT, 1, "73500", Screen.SEARCH));
    }

    @Test void rejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderDraft("005930", "삼성전자", OrderSide.BUY,
                        OrderType.LIMIT, 0, "73500", Screen.SEARCH));
    }
}
