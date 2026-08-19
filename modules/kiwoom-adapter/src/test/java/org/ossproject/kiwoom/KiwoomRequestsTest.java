package org.ossproject.kiwoom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.finance.model.OrderCommand;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderType;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KiwoomRequestsTest {

    @Test
    @DisplayName("매수는 매수 API, 매도는 매도 API 로 간다")
    void picksOrderApiBySide() {
        assertEquals(KiwoomApi.BUY_ORDER, KiwoomRequests.orderApi(OrderSide.BUY));
        assertEquals(KiwoomApi.SELL_ORDER, KiwoomRequests.orderApi(OrderSide.SELL));
    }

    @Test
    @DisplayName("지정가는 0, 시장가는 3 코드를 쓴다")
    void mapsTradeTypeCode() {
        assertEquals("0", KiwoomRequests.tradeType(OrderType.LIMIT));
        assertEquals("3", KiwoomRequests.tradeType(OrderType.MARKET));
    }

    @Test
    @DisplayName("지정가 주문은 주문단가를 채운다")
    void buildsLimitOrderBody() {
        OrderCommand command = OrderCommand.limit("005930", "삼성전자", OrderSide.BUY,
                10, new BigDecimal("73500"));

        String body = KiwoomRequests.placeOrder(command);

        assertTrue(body.contains("\"stk_cd\":\"005930\""));
        assertTrue(body.contains("\"ord_qty\":\"10\""));
        assertTrue(body.contains("\"trde_tp\":\"0\""));
        assertTrue(body.contains("\"ord_uv\":\"73500\""));
        assertTrue(body.contains("\"dmst_stex_tp\":\"KRX\""));
    }

    @Test
    @DisplayName("시장가 주문은 주문단가를 비워 보낸다")
    void buildsMarketOrderBodyWithoutPrice() {
        OrderCommand command = OrderCommand.market("005930", "삼성전자", OrderSide.SELL, 5);

        String body = KiwoomRequests.placeOrder(command);

        assertTrue(body.contains("\"trde_tp\":\"3\""));
        assertTrue(body.contains("\"ord_uv\":\"\""));
    }

    @Test
    @DisplayName("취소 요청에 원주문번호와 취소수량을 담는다")
    void buildsCancelBody() {
        String body = KiwoomRequests.cancelOrder("O123", "005930", 5);

        assertTrue(body.contains("\"orig_ord_no\":\"O123\""));
        assertTrue(body.contains("\"cncl_qty\":\"5\""));
    }

    @Test
    @DisplayName("취소 수량이 0 이하이면 전량 취소로 보낸다")
    void cancelsFullQuantityWhenNonPositive() {
        assertTrue(KiwoomRequests.cancelOrder("O123", "005930", 0).contains("\"cncl_qty\":\"0\""));
        assertTrue(KiwoomRequests.cancelOrder("O123", "005930", -1).contains("\"cncl_qty\":\"0\""));
    }

    @Test
    @DisplayName("원주문번호가 없으면 취소 요청을 만들 수 없다")
    void rejectsCancelWithoutOrderId() {
        assertThrows(IllegalArgumentException.class, () -> KiwoomRequests.cancelOrder("", "005930", 1));
        assertThrows(IllegalArgumentException.class, () -> KiwoomRequests.cancelOrder(null, "005930", 1));
    }

    @Test
    @DisplayName("종목코드만으로 조회 요청을 만든다")
    void buildsSymbolRequest() {
        assertEquals("{\"stk_cd\":\"005930\"}", KiwoomRequests.bySymbol("005930"));
    }

    @Test
    @DisplayName("일봉 요청에 기준일자와 수정주가 여부를 담는다")
    void buildsDailyChartRequest() {
        String body = KiwoomRequests.dailyChart("005930", "20260819", true);

        assertTrue(body.contains("\"base_dt\":\"20260819\""));
        assertTrue(body.contains("\"upd_stkpc_tp\":\"1\""));
    }

    @Test
    @DisplayName("미체결 조회는 종목이 없으면 전체 조회로 보낸다")
    void buildsUnfilledOrdersRequestForAll() {
        String body = KiwoomRequests.unfilledOrders(null);

        assertTrue(body.contains("\"all_stk_tp\":\"0\""));
        assertFalse(body.contains("stk_cd"));
    }

    @Test
    @DisplayName("미체결 조회에 종목을 지정하면 종목코드를 담는다")
    void buildsUnfilledOrdersRequestForSymbol() {
        String body = KiwoomRequests.unfilledOrders("005930");

        assertTrue(body.contains("\"all_stk_tp\":\"1\""));
        assertTrue(body.contains("\"stk_cd\":\"005930\""));
    }
}
