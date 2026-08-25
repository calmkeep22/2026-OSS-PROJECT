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
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.PricePoint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @DisplayName("분봉에서는 접근성 요약에도 날짜와 시각을 함께 표시한다")
    void intradaySummaryIncludesTime() {
        JavaFxToolkit.onFxThread(() -> {
            BigDecimal base = BigDecimal.valueOf(70_000);
            chart = new CandlestickChartView(List.of(
                    new PricePoint(LocalDate.of(2026, 8, 24), base,
                            base.add(BigDecimal.valueOf(500)), base.subtract(BigDecimal.valueOf(500)),
                            base.add(BigDecimal.valueOf(100)), 1_000,
                            LocalDateTime.of(2026, 8, 24, 9, 0)),
                    new PricePoint(LocalDate.of(2026, 8, 24), base,
                            base.add(BigDecimal.valueOf(500)), base.subtract(BigDecimal.valueOf(500)),
                            base.add(BigDecimal.valueOf(100)), 1_100,
                            LocalDateTime.of(2026, 8, 24, 9, 5))));
            chart.setInterval(CandleInterval.MINUTE_5);
        });

        assertTrue(summary().contains("5분봉"), summary());
        assertTrue(summary().contains("2026-08-24 09:00"), summary());
        assertTrue(summary().contains("2026-08-24 09:05"), summary());
    }

    /**
     * 왼쪽 방향키로 과거로 간다.
     *
     * <p>처음에는 마지막 캔들 오른쪽에 다음 봉이 들어올 빈 공간이 있다. 그 칸을 지나는
     * 동안에도 마지막 캔들은 화면에 남아 있으므로 아직 최신 구간이다. 빈 공간을 다 지나야
     * 과거 구간으로 들어간다.
     */
    @Test
    @DisplayName("왼쪽 방향키로 과거로 이동한다")
    void arrowKeyMovesToPast() {
        String before = summary();

        pressKey(KeyCode.LEFT, false);
        assertNotEquals(before, summary(), "한 칸만 움직여도 보이는 구간이 바뀌어야 합니다");

        for (int guard = 0; guard < 40 && atLatest(); guard++) {
            pressKey(KeyCode.LEFT, false);
        }

        assertFalse(atLatest(), "빈 공간을 지나 계속 가면 최신이 아니어야 합니다");
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

    /**
     * 과거를 받아 와도 요청이 되풀이되지 않는다.
     *
     * <p>받아 온 만큼 화면을 넓히거나 시작 위치를 0으로 되돌리면, 그 자리가 다시 "더 받아야
     * 하는 위치" 로 읽혀 상한에 닿을 때까지 요청이 이어진다. 그렇게 쌓인 수천 개가 한
     * 화면에 눌리면 캔들이 납작한 선이 되어 아무것도 읽을 수 없다.
     */
    @Test
    @DisplayName("과거를 이어 받아도 요청이 되풀이되지 않는다")
    void doesNotLoopWhenOlderDataArrives() {
        int[] requests = new int[1];
        List<PricePoint> loaded = new ArrayList<>(points());

        JavaFxToolkit.onFxThread(() -> chart.setOlderDataRequest(() -> {
            requests[0]++;
            // 실제 화면처럼, 요청을 받으면 과거를 앞에 붙여 다시 넣는다.
            List<PricePoint> older = new ArrayList<>();
            LocalDate start = loaded.get(0).date().minusDays(100);
            for (int i = 0; i < 100; i++) {
                BigDecimal base = BigDecimal.valueOf(60_000 + i * 10L);
                older.add(new PricePoint(start.plusDays(i), base, base.add(BigDecimal.valueOf(500)),
                        base.subtract(BigDecimal.valueOf(500)), base.add(BigDecimal.valueOf(100)),
                        1_000L + i));
            }
            older.addAll(loaded);
            loaded.clear();
            loaded.addAll(older);
            chart.setPoints(older);
        }));

        // 가장 과거로 한 번 이동한다. 이때 요청이 나가고 과거가 앞에 붙는다.
        pressKey(KeyCode.HOME, false);

        assertTrue(requests[0] > 0, "가장 과거로 가면 과거를 요청해야 합니다");
        // 붙인 뒤에는 보던 캔들을 따라가므로 왼쪽 끝에서 벗어난다. 더 조작하지 않았는데
        // 요청이 이어지면 스스로 되풀이되고 있다는 뜻이다.
        int afterFirstMove = requests[0];
        assertTrue(afterFirstMove <= 2,
                "한 번 이동에 요청이 한두 번이어야 합니다. 실제 " + afterFirstMove + "회");

        // 조작 없이 시간이 지나도(다시 그려도) 요청이 늘지 않아야 한다.
        JavaFxToolkit.onFxThread(() -> chart.setPriceScale(1.2));
        assertEquals(afterFirstMove, requests[0],
                "조작 없이 요청이 늘면 안 됩니다");
    }

    @Test
    @DisplayName("한 캔들보다 작게 끌어도 조금씩 움직인다")
    void dragsSmoothlyBelowOneCandle() {
        String before = summary();

        // 캔들 하나 폭보다 훨씬 작은 거리. 캔들 단위로 끊으면 아무 일도 일어나지 않는다.
        drag(500, 505);

        assertNotEquals(before, summary(), "작게 끌어도 흐르듯 움직여야 합니다");
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
    @DisplayName("최신 봉 오른쪽에 제한된 미래 빈 공간을 둔다")
    void allowsBoundedFutureSpace() {
        assertTrue(summary().contains("미래 빈 공간"), summary());

        // 기본 여백보다 두 칸 더 갈 수 있지만 끝없이 미래로 이동하지는 않는다.
        pressKey(KeyCode.RIGHT, false);
        String moved = summary();
        assertTrue(moved.contains("미래 빈 공간"), moved);
        for (int i = 0; i < 20; i++) pressKey(KeyCode.RIGHT, false);
        String atLimit = summary();
        pressKey(KeyCode.RIGHT, false);

        assertEquals(atLimit, summary(), "미래 빈 공간은 정해진 칸을 넘어가면 안 됩니다");
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

    /** 접근성 요약에 적힌 "N개" 를 읽어 지금 몇 개를 보고 있는지 알아낸다. */
    private int visibleCandles() {
        // 정규식을 쓰지 않는다. "N개" 앞의 숫자만 뒤에서부터 세면 되고, 이 검사 하나
        // 때문에 이스케이프가 낀 문자열을 들일 이유가 없다.
        String text = summary();
        int mark = text.indexOf("개");
        assertTrue(mark > 0, text);
        int from = mark;
        while (from > 0 && Character.isDigit(text.charAt(from - 1))) {
            from--;
        }
        assertTrue(from < mark, text);
        return Integer.parseInt(text.substring(from, mark));
    }

    /**
     * 실제로 났던 고장이다.
     *
     * <p>실시간 값이 올 때마다 보이는 개수가 30으로 되돌아갔다. 넓게 펼쳐 놓고 보던
     * 사람은 틱이 한 번 올 때마다 처음부터 다시 확대해야 했고, 1분봉처럼 자주 오는
     * 기간에서는 사실상 확대를 쓸 수 없었다.
     */
    @Test
    @DisplayName("새 값이 와도 보던 배율을 지킨다")
    void keepsTheZoomWhenPointsArrive() {
        JavaFxToolkit.onFxThread(() -> {
            chart.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN,
                    false, true, false, false));
            chart.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN,
                    false, true, false, false));
        });
        int widened = visibleCandles();
        assertNotEquals(30, widened, "먼저 배율을 바꿔 두어야 검사가 성립한다");

        JavaFxToolkit.onFxThread(() -> chart.setPoints(points()));

        assertEquals(widened, visibleCandles(), "실시간 값 하나에 배율이 되돌아갔다");
    }

    /**
     * 크게 보기 창이 이 통로로 실시간 값을 받는다. 실시간 구독은 자리가 하나뿐이라
     * 창이 따로 구독하면 원래 차트 쪽 연결이 끊긴다.
     */
    @Test
    @DisplayName("값이 바뀌면 붙어 있는 곳으로 함께 보낸다")
    void forwardsPointsToTheListener() {
        List<List<PricePoint>> received = new ArrayList<>();
        JavaFxToolkit.onFxThread(() -> {
            chart.setPointsListener(received::add);
            chart.setPoints(points());
        });

        assertEquals(1, received.size());
        assertEquals(points().size(), received.get(0).size());
    }

    @Test
    @DisplayName("떼고 나면 더 보내지 않는다")
    void stopsForwardingOnceDetached() {
        List<List<PricePoint>> received = new ArrayList<>();
        JavaFxToolkit.onFxThread(() -> {
            chart.setPointsListener(received::add);
            chart.setPoints(points());
            chart.setPointsListener(null);
            chart.setPoints(points());
        });

        assertEquals(1, received.size(), "닫힌 창을 계속 그리면 안 된다");
    }

    @Test
    @DisplayName("축척을 좁히면 세로로 끌어 잘린 부분을 볼 수 있다")
    void dragsVerticallyOnceTheScaleIsNarrowed() {
        // 자동 맞춤에서는 모든 캔들이 들어오므로 옮길 것이 없다.
        dragVertically(400, 250);
        assertEquals(0.0, priceOffset(), 1e-9, "자동 맞춤에서는 움직이지 않아야 합니다");

        JavaFxToolkit.onFxThread(() -> chart.setPriceScale(0.5));

        // 위로 끌면 내용이 손을 따라 올라가 아래쪽 잘린 캔들이 드러난다.
        dragVertically(400, 250);
        assertTrue(priceOffset() < 0, "세로로 끌면 가격 창이 움직여야 합니다. 실제 " + priceOffset());

        // 자동 맞춤으로 돌아오면 잘릴 것이 없으니 옮긴 것도 되돌린다.
        JavaFxToolkit.onFxThread(() -> chart.setPriceScale(1.0));
        assertEquals(0.0, priceOffset(), 1e-9, "자동 맞춤은 옮긴 자리를 되돌려야 합니다");
    }

    @Test
    @DisplayName("가격 창은 화면 밖으로 끝없이 밀리지 않는다")
    void clampsThePriceOffset() {
        JavaFxToolkit.onFxThread(() -> {
            chart.setPriceScale(0.5);
            chart.setPriceOffset(9_999);
        });
        assertTrue(priceOffset() <= 2.0, "위로 묶여야 합니다. 실제 " + priceOffset());

        JavaFxToolkit.onFxThread(() -> chart.setPriceOffset(-9_999));
        assertTrue(priceOffset() >= -2.0, "아래로도 묶여야 합니다. 실제 " + priceOffset());

        // 화면 맞춤은 축척과 옮긴 자리를 함께 되돌린다.
        JavaFxToolkit.onFxThread(chart::resetView);
        assertEquals(0.0, priceOffset(), 1e-9);
    }

    private double priceOffset() {
        double[] captured = new double[1];
        JavaFxToolkit.onFxThread(() -> captured[0] = chart.priceOffset());
        return captured[0];
    }

    private void dragVertically(double fromY, double toY) {
        JavaFxToolkit.onFxThread(() -> {
            var canvas = chart.getChildrenUnmodifiable().get(0);
            canvas.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 300, fromY, 300, fromY,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, true, false, true, null));
            canvas.fireEvent(new MouseEvent(MouseEvent.MOUSE_DRAGGED, 300, toY, 300, toY,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, true, false, true, null));
            canvas.fireEvent(new MouseEvent(MouseEvent.MOUSE_RELEASED, 300, toY, 300, toY,
                    MouseButton.PRIMARY, 1, false, false, false, false,
                    false, false, false, true, false, true, null));
        });
    }

    @Test
    @DisplayName("세로 축척은 뒤집히지 않게 범위를 묶는다")
    void clampsThePriceScale() {
        JavaFxToolkit.onFxThread(() -> {
            chart.setPriceScale(-5);
            chart.setPriceScale(0);
            chart.setPriceScale(9_999);
            chart.setPriceScale(1.0);
        });
        // 값이 튀면 그리는 도중 예외가 난다. 여기까지 왔으면 통과다.
        assertTrue(summary().startsWith("캔들 차트."), summary());
    }

    @Test
    @DisplayName("거래량을 꺼도 값과 요약은 그대로다")
    void keepsDataWhenVolumeIsHidden() {
        String before = summary();

        JavaFxToolkit.onFxThread(() -> chart.setShowVolume(false));

        assertEquals(before, summary(), "그리지 않는 것과 값이 없는 것은 다르다");
    }
}
