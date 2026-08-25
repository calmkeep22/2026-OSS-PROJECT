package org.ossproject.desktop.view.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.desktop.viewmodel.OrderDraftViewModel;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.order.OrderType;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkit.class)
class OrderFormViewTest {

    @Test
    @DisplayName("지정가일 때 호가표에서 고른 가격을 주문 초안에 반영한다")
    void appliesAnOrderBookPriceToALimitOrder() {
        JavaFxToolkit.onFxThread(() -> {
            OrderDraftViewModel model = model(OrderType.LIMIT, "257000");
            OrderFormView view = view(model);
            view.create();

            assertTrue(view.selectLimitPrice(new BigDecimal("258500")));
            assertEquals("258500", model.draft().price());
        });
    }

    @Test
    @DisplayName("시장가일 때는 호가표 가격을 주문 초안에 넣지 않는다")
    void ignoresAnOrderBookPriceForAMarketOrder() {
        JavaFxToolkit.onFxThread(() -> {
            OrderDraftViewModel model = model(OrderType.MARKET, "257000");
            OrderFormView view = view(model);
            view.create();

            assertFalse(view.selectLimitPrice(new BigDecimal("258500")));
            assertEquals("257000", model.draft().price());
        });
    }

    @Test
    @DisplayName("현재가와 한 호가 단추로 지정가를 빠르게 조절한다")
    void offersPriceShortcuts() {
        JavaFxToolkit.onFxThread(() -> {
            OrderDraftViewModel model = model(OrderType.LIMIT, "257000");
            OrderFormView view = new OrderFormView(model, false, BigDecimal::toPlainString,
                    ignored -> { }, ignored -> { }, ignored -> { },
                    new OrderFormView.PriceShortcuts(
                            () -> Optional.of(new BigDecimal("257500")),
                            () -> Optional.of(new BigDecimal("258000")),
                            () -> Optional.of(new BigDecimal("257000")),
                            () -> Optional.of(new BigDecimal("500"))));
            VBox root = view.create();

            ((Button) root.lookup("#order-price-current")).fire();
            assertEquals("257500", model.draft().price());
            ((Button) root.lookup("#order-price-up")).fire();
            assertEquals("258000", model.draft().price());
        });
    }

    @Test
    @DisplayName("수량 빠른 선택과 주문 검토 단축키가 동작한다")
    void offersQuantityAndPreviewShortcuts() {
        JavaFxToolkit.onFxThread(() -> {
            OrderDraftViewModel model = model(OrderType.LIMIT, "257000");
            AtomicInteger previews = new AtomicInteger();
            OrderFormView view = new OrderFormView(model, false, BigDecimal::toPlainString,
                    ignored -> previews.incrementAndGet(), ignored -> { }, ignored -> { });
            VBox root = view.create();

            ((Button) root.lookup("#order-quantity-10")).fire();
            assertEquals(10, model.draft().quantity());
            root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, true, false, false));
            assertEquals(1, previews.get());
        });
    }

    @Test
    @DisplayName("읽기 전용 종목 정보는 첫 탭 순서에서 제외한다")
    void skipsReadOnlyIdentityFieldsInTheTabOrder() {
        JavaFxToolkit.onFxThread(() -> {
            VBox root = view(model(OrderType.LIMIT, "257000")).create();
            long focusableReadOnlyFields = root.lookupAll(".text-field").stream()
                    .filter(TextField.class::isInstance).map(TextField.class::cast)
                    .filter(field -> !field.isEditable() && field.isFocusTraversable()).count();
            assertEquals(0, focusableReadOnlyFields);
        });
    }

    private static OrderDraftViewModel model(OrderType type, String price) {
        return new OrderDraftViewModel(new OrderDraft("005930", "삼성전자", OrderSide.BUY,
                type, 1, price, Screen.TRADING), Optional::empty);
    }

    private static OrderFormView view(OrderDraftViewModel model) {
        return new OrderFormView(model, false, BigDecimal::toPlainString,
                ignored -> { }, ignored -> { }, ignored -> { });
    }
}
