package org.ossproject.desktop.view.screen;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.market.StockDetail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 종목·현재가·고저·주문을 한 줄에 담으면서 주문 단추가 오른쪽 끝으로 밀려났다.
 *
 * <p>값이 길어질수록 왼쪽 묶음이 넓어지는데, 그만큼 오른쪽이 잘려 나가면 매수를 누를
 * 방법이 없다. 값의 길이는 종목마다 다르므로 여섯 자리 가격으로 세워 본다.
 */
@ExtendWith(JavaFxToolkit.class)
class StockScreenViewTest {

    private static StockDetail samsungFire() {
        return new StockDetail("000810", "삼성화재",
                new BigDecimal("643000"), new BigDecimal("7000"), new BigDecimal("1.10"),
                PriceDirection.UP, new BigDecimal("636000"), new BigDecimal("650000"),
                new BigDecimal("623000"), 34_399L, Instant.parse("2026-08-25T04:00:00Z"));
    }

    private static VBox screen(double width) {
        StockScreenView view = new StockScreenView(samsungFire(), "KRX",
                price -> String.format("%,d원", price.longValue()),
                new Button("\u2605"), () -> { }, () -> { }, (text, key) -> { });
        VBox body = view.create(new Label("차트"), new Label("호가"), new Label("체결"));
        // 실제 앱과 같은 구조다. 본문은 가로 스크롤이 없는 ScrollPane 안에 들어가므로,
        // 넘치면 스크롤되지 않고 그대로 잘린다.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        StackPane host = new StackPane(scroll);
        Scene scene = new Scene(host, width, 900);
        scene.getStylesheets().add(
                StockScreenViewTest.class.getResource("/styles/application.css").toExternalForm());
        host.applyCss();
        host.layout();
        return body;
    }

    private static Button findButton(javafx.scene.Node node, String text) {
        if (node instanceof Button button && text.equals(button.getText())) {
            return button;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                Button found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("값이 길어도 매수 단추가 화면 안에 남는다")
    void keepsTheOrderButtonsInsideTheWindow() {
        JavaFxToolkit.onFxThread(() -> {
            for (double width : new double[] {1920, 1440, 1280, 1024, 900, 800, 700}) {
                VBox body = screen(width);
                Button buy = findButton(body, "매수");
                assertNotNull(buy, "매수 단추를 찾지 못했습니다");

                double right = buy.localToScene(buy.getBoundsInLocal()).getMaxX();
                assertTrue(right <= width,
                        "폭 " + width + " 에서 매수 단추가 화면 밖으로 나갔습니다. 오른쪽 끝 " + right);
                assertTrue(buy.getWidth() > 0, "폭 " + width + " 에서 매수 단추가 사라졌습니다");
            }
        });
    }

    @Test
    @DisplayName("종목 상세의 주문·관심종목·탭 단축키가 동작한다")
    void supportsStockDetailShortcuts() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicInteger buys = new AtomicInteger();
            AtomicInteger sells = new AtomicInteger();
            AtomicInteger watches = new AtomicInteger();
            Button watch = new Button("관심종목 추가");
            watch.setOnAction(event -> watches.incrementAndGet());
            StockScreenView view = new StockScreenView(samsungFire(), "KRX",
                    price -> String.format("%,d원", price.longValue()), watch,
                    buys::incrementAndGet, sells::incrementAndGet, (text, key) -> { });
            VBox body = view.create(new Label("차트"), new Label("호가"), new Label("체결"));

            body.fireEvent(key(KeyCode.B, false, true));
            body.fireEvent(key(KeyCode.B, true, true));
            body.fireEvent(key(KeyCode.W, false, true));
            TabPane tabs = findTabPane(body);
            assertNotNull(tabs);
            assertEquals(0, tabs.getSelectionModel().getSelectedIndex());
            body.fireEvent(key(KeyCode.TAB, false, false, true));

            assertEquals(1, buys.get());
            assertEquals(1, sells.get());
            assertEquals(1, watches.get());
            assertEquals(1, tabs.getSelectionModel().getSelectedIndex());
        });
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean alt) {
        return key(code, shift, alt, false);
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean alt, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false);
    }

    private static TabPane findTabPane(javafx.scene.Node node) {
        if (node instanceof TabPane tabs) return tabs;
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                TabPane found = findTabPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
