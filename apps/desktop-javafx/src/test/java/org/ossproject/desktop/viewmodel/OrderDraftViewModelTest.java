package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Balance;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderType;
import org.ossproject.finance.model.Position;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 비율 단추가 몇 주를 채우는지 검사한다.
 *
 * <p>이 셈은 화면 안에 갇혀 있었다. 주문은 되돌릴 수 없는 동작이라, 여기가 틀리면
 * 사용자가 의도하지 않은 수량으로 주문한다.
 *
 * <p>못 구할 때 0 을 돌려주지 않는지도 함께 본다. 0 주 주문을 만들어 검증에서 걸리게
 * 하는 것보다, 왜 못 하는지 그 자리에서 말하는 편이 낫다.
 */
class OrderDraftViewModelTest {

    private static final BigDecimal PRICE = new BigDecimal("70000");

    private static OrderDraft draft() {
        return new OrderDraft("005930", "삼성전자", OrderSide.BUY, OrderType.LIMIT, 1,
                "70000", Screen.DASHBOARD);
    }

    private static Account accountWith(String cash, Position... positions) {
        return new Account("1234567890", Balance.of(new BigDecimal(cash)), List.of(positions));
    }

    private static Position holding(String symbol, long quantity, long locked) {
        return new Position(symbol, "삼성전자", quantity, locked, PRICE, PRICE, null);
    }

    private static OrderDraftViewModel viewModel(Account account) {
        OrderDraftViewModel model = new OrderDraftViewModel(draft(), () -> Optional.of(PRICE));
        if (account != null) {
            model.setAccount(account);
        }
        return model;
    }

    @Test
    @DisplayName("매수는 주문 가능 금액을 가격으로 나눈다")
    void buyDividesTheOrderableAmount() {
        OrderDraftViewModel model = viewModel(accountWith("700000"));

        // 700,000 / 70,000 = 10주. 그중 50퍼센트.
        assertEquals(5, model.quantityFor(50, OrderSide.BUY, OrderType.LIMIT, "70000").quantity());
        assertEquals(10, model.quantityFor(100, OrderSide.BUY, OrderType.LIMIT, "70000").quantity());
    }

    @Test
    @DisplayName("매도는 보유수량을 나눈다. 주문 가능 금액이 아니다")
    void sellDividesTheHolding() {
        OrderDraftViewModel model = viewModel(
                accountWith("700000", holding("005930", 40, 0)));

        assertEquals(10, model.quantityFor(25, OrderSide.SELL, OrderType.LIMIT, "70000").quantity());
    }

    /** 이미 걸어 둔 주문에 묶인 수량은 팔 수 없다. 그걸 세면 주문이 거절된다. */
    @Test
    @DisplayName("매도 수량은 묶이지 않은 것만 센다")
    void sellCountsOnlyWhatIsFree() {
        OrderDraftViewModel model = viewModel(
                accountWith("700000", holding("005930", 40, 30)));

        assertEquals(10, model.quantityFor(100, OrderSide.SELL, OrderType.LIMIT, "70000").quantity());
    }

    @Test
    @DisplayName("보유수량이 없으면 이유를 말한다")
    void saysWhyWhenThereIsNothingToSell() {
        OrderDraftViewModel.Suggestion suggestion =
                viewModel(accountWith("700000")).quantityFor(100, OrderSide.SELL, OrderType.LIMIT, "70000");

        assertFalse(suggestion.available());
        assertEquals("매도 가능한 보유수량이 없습니다.", suggestion.reason());
    }

    @Test
    @DisplayName("가격을 읽을 수 없으면 수량을 지어내지 않는다")
    void refusesToGuessWithoutAPrice() {
        OrderDraftViewModel model = viewModel(accountWith("700000"));

        assertFalse(model.quantityFor(100, OrderSide.BUY, OrderType.LIMIT, "가격").available());
        assertFalse(model.quantityFor(100, OrderSide.BUY, OrderType.LIMIT, "0").available());
        assertFalse(model.quantityFor(100, OrderSide.BUY, OrderType.LIMIT, "").available());
    }

    /** 시장가는 사용자가 가격을 적지 않는다. 현재가로 센다. */
    @Test
    @DisplayName("시장가는 현재가로 센다")
    void marketOrdersUseTheCurrentPrice() {
        OrderDraftViewModel model = viewModel(accountWith("700000"));

        assertEquals(10, model.quantityFor(100, OrderSide.BUY, OrderType.MARKET, "").quantity());
    }

    /** 현재가를 아직 못 받았을 수 있다. 0 으로 치면 엉뚱한 수량이 나온다. */
    @Test
    @DisplayName("현재가를 모르면 시장가 수량도 내놓지 않는다")
    void refusesMarketQuantityWithoutACurrentPrice() {
        OrderDraftViewModel model = new OrderDraftViewModel(draft(), Optional::empty);
        model.setAccount(accountWith("700000"));

        assertFalse(model.quantityFor(100, OrderSide.BUY, OrderType.MARKET, "").available());
    }

    @Test
    @DisplayName("계좌를 아직 못 받았으면 수량을 내놓지 않는다")
    void refusesBeforeTheAccountArrives() {
        OrderDraftViewModel.Suggestion suggestion =
                viewModel(null).quantityFor(100, OrderSide.BUY, OrderType.LIMIT, "70000");

        assertFalse(suggestion.available());
        assertTrue(suggestion.reason().contains("계좌"), suggestion.reason());
    }

    /** 살 수 있는 것이 1주뿐인데 10퍼센트를 누르면 0 주가 된다. 0 주 주문은 낼 수 없다. */
    @Test
    @DisplayName("반올림해서 0 주가 되면 1 주로 올린다")
    void neverSuggestsZeroShares() {
        OrderDraftViewModel model = viewModel(accountWith("70000"));

        assertEquals(1, model.quantityFor(10, OrderSide.BUY, OrderType.LIMIT, "70000").quantity());
    }

    @Test
    @DisplayName("예상 금액은 가격 곱하기 수량이다")
    void estimatesTheAmount() {
        OrderDraftViewModel model = viewModel(null);

        assertEquals(new BigDecimal("210000"),
                model.estimatedAmount("70000", 3).orElseThrow());
        assertEquals(new BigDecimal("210000"),
                model.estimatedAmount("70,000", 3).orElseThrow(), "천 단위 쉼표가 섞여 들어온다.");
    }

    /** 0 원으로 적으면 사용자는 공짜로 살 수 있다고 읽는다. */
    @Test
    @DisplayName("가격을 못 읽으면 예상 금액을 비운다")
    void leavesTheEstimateEmptyWithoutAPrice() {
        OrderDraftViewModel model = viewModel(null);

        assertTrue(model.estimatedAmount("가격", 3).isEmpty());
        assertTrue(model.estimatedAmount("70000", 0).isEmpty());
    }

    /**
     * 사용자가 가격을 지우는 도중 빈 문자열이 잠깐 들어온다. 그때 초안을 비우면 다음
     * 글자에서 되살릴 근거가 없다.
     */
    @Test
    @DisplayName("값이 온전하지 않으면 초안을 바꾸지 않는다")
    void keepsTheDraftWhileTheUserIsTyping() {
        OrderDraftViewModel model = viewModel(null);
        model.update(OrderSide.SELL, OrderType.MARKET, 7, "80000");

        model.update(OrderSide.SELL, OrderType.MARKET, 7, "");
        model.update(null, OrderType.MARKET, 7, "80000");
        model.update(OrderSide.SELL, OrderType.MARKET, 0, "80000");

        assertEquals(7, model.draft().quantity());
        assertEquals("80000", model.draft().price());
        assertEquals(OrderSide.SELL, model.draft().side());
    }

    /** 종목은 폼에서 바꿀 수 없다. 초안을 고쳐도 그대로여야 한다. */
    @Test
    @DisplayName("초안을 고쳐도 종목은 그대로다")
    void neverChangesTheStock() {
        OrderDraftViewModel model = viewModel(null);
        model.update(OrderSide.SELL, OrderType.MARKET, 5, "80000");

        assertEquals("005930", model.draft().symbol());
        assertEquals("삼성전자", model.draft().name());
    }
}
