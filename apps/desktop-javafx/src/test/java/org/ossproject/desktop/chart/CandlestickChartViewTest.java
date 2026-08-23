package org.ossproject.desktop.chart;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.finance.model.market.PricePoint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkit.class)
class CandlestickChartViewTest {

    private CandlestickChartView chart;

    /** 200일치 일봉. 확대해도 좌우로 움직일 여유가 넉넉하다. */
    private static List<PricePoint> points() {
        List<PricePoint> values = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 200; i++) {
            BigDecimal base = BigDecimal.valueOf(70_000 + i * 10L);
            values.add(new PricePoint(date.plusDays(i), base, base.add(BigDecimal.valueOf(500)),
                    base.subtract(BigDecimal.valueOf(500)), base.add(BigDecimal.valueOf(100)),
                    1_000L + i));
        }
        return values;
    }

    @BeforeEach
    void setUp() {
        JavaFxToolkit.onFxThread(() -> {
            chart = new CandlestickChartView(points());
            StackPane root = new StackPane(chart);
            new Scene(root, 900, 500);
            root.applyCss();
            root.layout();
        });
    }

    /** 접근성 요약. 보이는 구간이 바뀌면 함께 바뀐다. */
    private String summary() {
        String[] captured = new String[1];
        JavaFxToolkit.onFxThread(() -> captured[0] = chart.getAccessibleText());
        return captured[0];
    }

    private boolean atLatest() {
        boolean[] captured = new boolean[1];
        JavaFxToolkit.onFxThread(() -> captured[0] = chart.isAtLatest());
        return captured[0];
    }

    private void pressKey(KeyCode code, boolean control) {
        JavaFxToolkit.onFxThread(() -> chart.fireEvent(new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false)));
    }

    private void drag(double fromX, double toX) {
        JavaFxToolkit.onFxThread(() -> {
            var canvas = chart.getChildrenUnmodifiable().get(0);
            canvas.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, fromX, 200, fromX, 200,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, true, false, true, null));
            canvas.fireEvent(new MouseEvent(MouseEvent.MOUSE_DRAGGED, toX, 200, toX, 200,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, true, false, true, null));
            canvas.fireEvent(new MouseEvent(MouseEvent.MOUSE_RELEASED, toX, 200, toX, 200,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    false, false, false, true, false, true, null));
        });
    }

    @Test
    @DisplayName("처음에는 최신 구간을 보여 준다")
    void startsAtLatest() {
        assertTrue(atLatest());
        assertTrue(summary().contains("최신 구간"), summary());
    }

    @Test
    @DisplayName("왼쪽 방향키로 과거로 이동한다")
    void arrowKeyMovesToPast() {
        String before = summary();

        pressKey(KeyCode.LEFT, false);

        assertNotEquals(before, summary());
        assertFalse(atLatest(), "과거로 옮겼으면 최신이 아니어야 합니다");
        assertTrue(summary().contains("과거 구간"), summary());
    }

    @Test
    @DisplayName("Ctrl과 방향키로 더 빠르게 이동한다")
    void controlArrowMovesFaster() {
        pressKey(KeyCode.LEFT, false);
        String afterOneStep = summary();

        pressKey(KeyCode.END, false);
        pressKey(KeyCode.LEFT, true);

        assertNotEquals(afterOneStep, summary(), "빠른 이동은 한 칸 이동과 달라야 합니다");
    }

    @Test
    @DisplayName("End 키로 최신 구간으로 돌아온다")
    void endReturnsToLatest() {
        pressKey(KeyCode.LEFT, true);
        assertFalse(atLatest());

        pressKey(KeyCode.END, false);

        assertTrue(atLatest());
    }

    @Test
    @DisplayName("Home 키로 가장 오래된 구간으로 간다")
    void homeGoesToOldest() {
        pressKey(KeyCode.HOME, false);

        assertFalse(atLatest());
        assertTrue(summary().contains("2026-01-01"), summary());
    }

    @Test
    @DisplayName("끌어서 좌우로 움직인다")
    void dragPans() {
        String before = summary();

        // 오른쪽으로 끌면 내용이 손을 따라와 과거가 보인다
        drag(300, 600);

        assertNotEquals(before, summary(), "끌면 구간이 움직여야 합니다");
        assertFalse(atLatest());
    }

    @Test
    @DisplayName("한 캔들보다 작게 끌어도 조금씩 움직인다")
    void dragsSmoothlyBelowOneCandle() {
        // 캔들 하나 폭보다 훨씬 작은 거리. 캔들 단위로 끊으면 아무 일도 일어나지 않는다.
        drag(500, 505);

        assertFalse(atLatest(), "작게 끌어도 흐르듯 움직여야 합니다");
    }

    @Test
    @DisplayName("조금씩 여러 번 끌면 누적되어 한 캔들을 넘어간다")
    void accumulatesSmallDrags() {
        String before = summary();

        for (int i = 0; i < 12; i++) {
            drag(500, 505);
        }

        assertNotEquals(before, summary(), "조금씩 끈 것이 쌓여 구간이 바뀌어야 합니다");
    }

    @Test
    @DisplayName("최신 구간에서는 더 오른쪽으로 가지 않는다")
    void doesNotPanBeyondLatest() {
        String before = summary();

        pressKey(KeyCode.RIGHT, false);
        pressKey(KeyCode.RIGHT, false);

        assertEquals(before, summary(), "최신을 넘어 갈 곳은 없습니다");
    }

    @Test
    @DisplayName("가장 오래된 구간에서는 더 왼쪽으로 가지 않는다")
    void doesNotPanBeforeOldest() {
        pressKey(KeyCode.HOME, false);
        String before = summary();

        pressKey(KeyCode.LEFT, false);

        assertEquals(before, summary());
    }

    @Test
    @DisplayName("Ctrl과 위아래 방향키로 확대하고 축소한다")
    void controlUpDownZooms() {
        String initial = summary();

        pressKey(KeyCode.UP, true);
        String zoomedIn = summary();
        assertNotEquals(initial, zoomedIn, "확대하면 보이는 캔들 수가 달라야 합니다");

        pressKey(KeyCode.DOWN, true);
        assertEquals(initial, summary(), "축소하면 원래 구간으로 돌아와야 합니다");
    }

    @Test
    @DisplayName("키보드로 조작할 수 있고 방법을 설명한다")
    void isKeyboardAccessible() {
        boolean[] focusable = new boolean[1];
        String[] help = new String[1];
        JavaFxToolkit.onFxThread(() -> {
            focusable[0] = chart.isFocusTraversable();
            help[0] = chart.getAccessibleHelp();
        });

        assertTrue(focusable[0], "마우스로만 되면 키보드 사용자가 쓸 수 없습니다");
        assertTrue(help[0].contains("방향키"), help[0]);
        assertTrue(help[0].contains("End"), help[0]);
    }

    @Test
    @DisplayName("새 데이터가 와도 과거를 보던 중이면 그 자리를 지킨다")
    void keepsPositionWhenNewDataArrives() {
        pressKey(KeyCode.HOME, false);
        String before = summary();

        JavaFxToolkit.onFxThread(() -> chart.setPoints(points()));

        assertEquals(before, summary(), "읽던 구간이 끌려가면 다시 찾아야 합니다");
    }

    @Test
    @DisplayName("최신을 보고 있었다면 새 데이터를 따라간다")
    void followsLatestWhenAtLatest() {
        assertTrue(atLatest());

        JavaFxToolkit.onFxThread(() -> chart.setPoints(points()));

        assertTrue(atLatest(), "최신을 보고 있었다면 계속 따라가야 합니다");
    }
}
