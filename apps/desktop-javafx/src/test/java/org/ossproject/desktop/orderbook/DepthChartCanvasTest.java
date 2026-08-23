package org.ossproject.desktop.orderbook;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.finance.model.orderbook.DepthChart;
import org.ossproject.finance.model.orderbook.DepthChartConfig;
import org.ossproject.finance.model.orderbook.OrderBook;
import org.ossproject.finance.model.orderbook.OrderBookLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkit.class)
class DepthChartCanvasTest {

    private static final Instant NOW = Instant.parse("2026-08-23T05:00:00Z");

    private DepthChartCanvas canvas;

    /** 중심 73,500 기준 100원 간격 10단계 호가창. */
    private static OrderBook book() {
        List<OrderBookLevel> levels = new ArrayList<>();
        for (int level = 1; level <= 10; level++) {
            levels.add(OrderBookLevel.of(level,
                    BigDecimal.valueOf(73_500 + 100L * level), 100L * level,
                    BigDecimal.valueOf(73_500 - 100L * level), 100L * level));
        }
        return OrderBook.of("005930", levels, NOW);
    }

    @BeforeEach
    void setUp() {
        JavaFxToolkit.onFxThread(() -> {
            canvas = new DepthChartCanvas();
            // 접근성 설명에서 보이는 구간을 읽으려면 실제로 배치되어 있어야 한다.
            StackPane root = new StackPane(canvas);
            new Scene(root, 600, 400);
            root.applyCss();
            root.layout();

            DepthChart chart = DepthChart.create(DepthChartConfig.defaults());
            canvas.update(chart.update(book(), new BigDecimal("73500")).view());
        });
    }

    /** 접근성 설명에 적힌 "지금 보이는 구간" 문장. 확대·이동 결과를 값으로 확인한다. */
    private String visibleRangeText() {
        String[] captured = new String[1];
        JavaFxToolkit.onFxThread(() -> captured[0] = canvas.getAccessibleHelp());
        return captured[0];
    }

    private void scroll(double deltaY, boolean zoomModifier) {
        JavaFxToolkit.onFxThread(() -> canvas.fireEvent(new ScrollEvent(
                ScrollEvent.SCROLL, 10, 10, 10, 10, false, zoomModifier, false, false,
                true, false, 0, deltaY, 0, deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null)));
    }

    private void pressKey(KeyCode code) {
        JavaFxToolkit.onFxThread(() -> canvas.fireEvent(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    @Test
    @DisplayName("처음에는 전체 구간이 보인다")
    void showsFullRangeInitially() {
        String text = visibleRangeText();

        assertTrue(text.contains("지금 보이는 구간"), "구간 안내가 있어야 합니다: " + text);
        assertTrue(text.contains("72,500") && text.contains("74,500"),
                "전체 축 범위가 보여야 합니다: " + text);
    }

    @Test
    @DisplayName("휠을 굴리면 보이는 가격 구간이 바뀐다")
    void wheelMovesVisibleRange() {
        scroll(40, true);    // 먼저 확대해야 옮길 여백이 생긴다 (양수가 확대)
        String before = visibleRangeText();

        scroll(-40, false);  // 아래로 이동

        assertNotEquals(before, visibleRangeText(), "휠을 굴리면 구간이 움직여야 합니다");
    }

    @Test
    @DisplayName("확대하면 보이는 구간이 좁아진다")
    void zoomNarrowsVisibleRange() {
        String before = visibleRangeText();

        scroll(40, true);

        String after = visibleRangeText();
        assertNotEquals(before, after);
        assertTrue(after.contains("확대") || !after.equals(before),
                "확대 후 구간이 달라야 합니다: " + after);
    }

    @Test
    @DisplayName("확대하지 않으면 이동할 여백이 없어 구간이 그대로다")
    void doesNotPanBeyondAxis() {
        String before = visibleRangeText();

        scroll(-40, false);
        scroll(-40, false);

        assertEquals(before, visibleRangeText(), "전체가 보이는 상태에서는 옮길 곳이 없습니다");
    }

    @Test
    @DisplayName("방향키로도 이동할 수 있다")
    void arrowKeysPan() {
        pressKey(KeyCode.PLUS);   // 확대
        String before = visibleRangeText();

        pressKey(KeyCode.DOWN);

        assertNotEquals(before, visibleRangeText(), "방향키로도 옮길 수 있어야 합니다");
    }

    @Test
    @DisplayName("더하기·빼기로 확대와 축소를 오간다")
    void plusMinusZoom() {
        String initial = visibleRangeText();

        pressKey(KeyCode.PLUS);
        assertNotEquals(initial, visibleRangeText());

        pressKey(KeyCode.MINUS);
        assertEquals(initial, visibleRangeText(), "축소하면 원래 구간으로 돌아와야 합니다");
    }

    @Test
    @DisplayName("Home 키로 처음 상태로 되돌린다")
    void homeResetsViewport() {
        String initial = visibleRangeText();
        pressKey(KeyCode.PLUS);
        pressKey(KeyCode.DOWN);
        assertNotEquals(initial, visibleRangeText());

        pressKey(KeyCode.HOME);

        assertEquals(initial, visibleRangeText());
    }

    @Test
    @DisplayName("키보드로 조작할 수 있고 조작 방법을 설명한다")
    void isKeyboardAccessible() {
        boolean[] focusable = new boolean[1];
        JavaFxToolkit.onFxThread(() -> focusable[0] = canvas.isFocusTraversable());

        assertTrue(focusable[0], "마우스로만 조작되면 키보드 사용자가 쓸 수 없습니다");
        String help = visibleRangeText();
        assertTrue(help.contains("방향키"), "조작 방법을 설명해야 합니다: " + help);
    }
}
