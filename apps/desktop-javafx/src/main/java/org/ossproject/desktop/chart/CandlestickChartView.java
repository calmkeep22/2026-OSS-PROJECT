package org.ossproject.desktop.chart;

import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.PricePoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * UI 전용 캔들 차트. 확대·축소, Crosshair, 거래량과 주요 보조지표를 그린다.
 * 키움이나 JavaFX Controller에 의존하지 않아 실제 데이터 연결 후에도 그대로 재사용한다.
 */
public final class CandlestickChartView extends Region {
    private static final double LEFT = 16;
    private static final double RIGHT = 86;
    private static final double TOP = 42;
    private static final double BOTTOM = 24;

    private final Canvas canvas = new Canvas();
    private List<PricePoint> points;
    private int visibleCount;
    /**
     * 보이는 구간의 첫 캔들 위치.
     *
     * <p>이 값이 없으면 언제나 마지막 캔들에 붙어 있게 되어 과거로 갈 방법이 없다.
     * 확대는 되는데 그 안에서 움직일 수 없는 상태가 된다.
     *
     * <p>정수가 아니라 소수로 둔다. 캔들 단위로 끊으면 끌 때 한 칸씩 툭툭 건너뛰어,
     * 화면 전체가 손을 따라 흐르는 느낌이 나지 않는다. 소수로 두면 캔들과 격자, 눈금이
     * 같은 양만큼 함께 밀린다.
     */
    private double startIndex;
    /**
     * 이번에 그릴 때 옆으로 밀 거리(픽셀).
     *
     * <p>캔들, 보조지표, 시간축이 <b>모두 같은 값만큼</b> 밀려야 화면 전체가 손을 따라
     * 함께 흐른다. 하나라도 빠지면 그 요소만 제자리에 남아 어긋나 보인다.
     */
    private double shiftPx;
    private Runnable viewportListener;
    /** 과거 구간이 더 필요할 때 실행할 동작. */
    private Runnable olderRequest;
    private double dragAnchorX = Double.NaN;
    private double dragAnchorStart;

    /**
     * 거래량 칸을 그릴지.
     *
     * <p>좁은 칸에서는 끈다. 세로가 모자라면 거래량 칸이 창 밖으로 밀려, 있으나 마나 한
     * 자리를 가격 차트에서 빼앗기만 한다. 끄더라도 값이 사라지지는 않는다 — 같은 값이
     * "접근 가능한 표" 의 거래량 칸에 그대로 있고, 크게 보기 창에서는 다시 켠다.
     */
    private boolean showVolume = true;

    /**
     * 세로 축척. 1이면 보이는 구간에 꼭 맞춘다.
     *
     * <p>1보다 크면 더 넓은 값 범위를 담아 캔들이 납작해지고, 작으면 좁은 범위를 늘려
     * 잔 움직임이 보인다. 오른쪽 가격축을 위아래로 끌어 바꾼다.
     */
    private double priceScale = 1.0;
    private double axisAnchorY = Double.NaN;
    private double axisAnchorScale = 1.0;

    /**
     * 가격 창을 위아래로 옮긴 정도. 보이는 값 폭을 1로 본 비율이다.
     *
     * <p>축척을 손으로 좁히면 위아래로 잘려 나가는 캔들이 생긴다. 옮길 방법이 없으면
     * 잘린 쪽은 영영 못 본다. 자동 맞춤(축척 1)에서는 늘 다 들어오므로 0으로 둔다.
     */
    private double priceOffset;
    private double dragAnchorY = Double.NaN;
    private double dragAnchorOffset;

    /**
     * 값이 바뀔 때 함께 받아 갈 곳. 크게 보기 창이 여기에 붙는다.
     *
     * <p>실시간 구독은 한 자리뿐이라 창이 따로 구독하면 원래 차트의 연결이 끊긴다.
     * 그래서 구독은 그대로 두고, 받은 값을 여기로 한 번 더 흘려보낸다.
     */
    private Consumer<List<PricePoint>> pointsListener;
    private double crossX = -1;
    private double crossY = -1;
    private boolean showMa = true;
    private boolean showBollinger;
    private boolean showRsi;
    private boolean showMacd;

    /** 한 번에 늘리거나 줄이는 캔들 수. */
    private static final int ZOOM_STEP = 4;
    /** 화면에 남길 최소 캔들 수. 너무 적으면 추세를 읽을 수 없다. */
    private static final int MIN_VISIBLE = 10;
    /** 최신 위치에서 다음 봉이 들어올 자리. */
    private static final int DEFAULT_FUTURE_SLOTS = 8;
    /** 오른쪽으로 더 이동할 수 있는 최대 미래 빈칸. */
    private static final int MAX_FUTURE_SLOTS = 10;

    /** 가격 창을 옮길 수 있는 한계. 화면 두 개 너머로는 나가지 않는다. */
    private static final double MAX_PRICE_OFFSET = 2.0;
    private static final double MIN_PRICE_SCALE = 0.15;
    private static final double MAX_PRICE_SCALE = 6.0;
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM/dd");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    /** X축과 상세 시각의 표시 단위. */
    private CandleInterval interval = CandleInterval.DAY;

    public CandlestickChartView(List<PricePoint> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("차트 데이터는 한 건 이상이어야 합니다.");
        }
        this.points = List.copyOf(points);
        this.visibleCount = Math.min(30, points.size());
        this.startIndex = latestStart();
        getChildren().add(canvas);
        setMinHeight(320);
        setPrefHeight(390);
        setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        setAccessibleText(buildAccessibleSummary());
        setAccessibleHelp("마우스 휠로 확대하고 끌어서 좌우로 움직입니다. 방향키로 한 칸씩, Ctrl과 방향키로 빠르게 이동하며 Ctrl과 위아래 방향키로 확대합니다. Home은 가장 오래된 구간, End는 최신 구간입니다. 표 탭에서 같은 데이터를 읽을 수 있습니다.");

        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
            crossX = event.getX();
            crossY = event.getY();
            draw();
        });
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            crossX = -1;
            crossY = -1;
            draw();
        });
        installInteractions();
    }

    // ------------------------------------------------------------------
    // 조작
    // ------------------------------------------------------------------

    /**
     * 확대와 이동을 등록한다.
     *
     * <p>널리 쓰이는 차트 도구와 같은 조작으로 맞췄다. 휠은 확대, 끌면 좌우로 움직이고,
     * Shift 와 함께 굴리면 휠만 있는 마우스로도 이동할 수 있다. Ctrl 과 함께 굴리면
     * 커서 아래 캔들이 제자리에 남은 채로 확대되어, 보던 구간을 놓치지 않는다.
     *
     * <p>마우스로만 되면 키보드 사용자가 쓸 수 없으므로 방향키로도 같은 조작을 할 수 있다.
     */
    private void installInteractions() {
        setFocusTraversable(true);

        canvas.setOnScroll(event -> {
            if (event.isShiftDown()) {
                panBy(event.getDeltaY() > 0 ? -ZOOM_STEP : ZOOM_STEP);
            } else if (event.isControlDown() || event.isMetaDown()) {
                zoomAt(event.getDeltaY() > 0, event.getX());
            } else {
                zoomAt(event.getDeltaY() > 0, Double.NaN);
            }
            event.consume();
        });

        canvas.setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            requestFocus();
            // 오른쪽 가격축 위에서 시작한 끌기는 이동이 아니라 세로 축척이다. 트레이딩뷰와
            // 같은 손놀림이라 따로 배울 것이 없다.
            if (event.getX() >= canvas.getWidth() - RIGHT) {
                axisAnchorY = event.getY();
                axisAnchorScale = priceScale;
                event.consume();
                return;
            }
            dragAnchorX = event.getX();
            dragAnchorStart = startIndex;
            dragAnchorY = event.getY();
            dragAnchorOffset = priceOffset;
            event.consume();
        });
        canvas.setOnMouseDragged(event -> {
            if (!Double.isNaN(axisAnchorY)) {
                // 아래로 끌면 넓은 범위를 담아 납작해지고, 위로 끌면 좁혀 늘어난다.
                double moved = event.getY() - axisAnchorY;
                setPriceScale(axisAnchorScale * Math.exp(moved / 220.0));
                event.consume();
                return;
            }
            if (Double.isNaN(dragAnchorX)) {
                return;
            }
            // 끌어당긴 거리를 캔들 수로 옮긴다. 반올림하지 않아야 손을 따라 매끄럽게
            // 흐른다. 오른쪽으로 끌면 과거가 보이도록 내용이 손을 따라오는 방향으로 맞춘다.
            double slot = slotWidth();
            if (slot <= 0) {
                return;
            }
            setStartIndex(dragAnchorStart + (dragAnchorX - event.getX()) / slot);
            // 축척을 손으로 좁혀 놓았으면 세로로도 따라온다. 잘려 나간 위아래를 볼
            // 방법이 이것뿐이다. 자동 맞춤에서는 잘릴 것이 없어 setPriceOffset 이
            // 그냥 돌아간다. 가로와 마찬가지로 내용이 손을 따라오는 방향이다.
            double panel = pricePanelHeight();
            if (panel > 0) {
                setPriceOffset(dragAnchorOffset + (event.getY() - dragAnchorY) / panel);
            }
            event.consume();
        });
        canvas.setOnMouseReleased(event -> {
            dragAnchorX = Double.NaN;
            dragAnchorY = Double.NaN;
            axisAnchorY = Double.NaN;
        });
        // 가격축을 두 번 누르면 자동 맞춤으로 되돌린다. 한참 끌고 나면 손으로 1.0 을
        // 다시 맞출 방법이 없다.
        canvas.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && event.getX() >= canvas.getWidth() - RIGHT) {
                setPriceScale(1.0);
                event.consume();
            }
        });

        setOnKeyPressed(event -> {
            KeyCode code = event.getCode();
            boolean fast = event.isControlDown() || event.isMetaDown();
            if (code == KeyCode.LEFT) {
                panBy(fast ? -visibleCount / 2 : -1);
            } else if (code == KeyCode.RIGHT) {
                panBy(fast ? visibleCount / 2 : 1);
            } else if (fast && code == KeyCode.UP) {
                zoomAt(true, Double.NaN);
            } else if (fast && code == KeyCode.DOWN) {
                zoomAt(false, Double.NaN);
            } else if (event.isShiftDown() && code == KeyCode.UP) {
                setPriceScale(priceScale / 1.2);
            } else if (event.isShiftDown() && code == KeyCode.DOWN) {
                setPriceScale(priceScale * 1.2);
            } else if (code == KeyCode.PAGE_UP) {
                // 창을 위로 올려 더 높은 값을 본다. 문서를 위로 넘기는 것과 같다.
                setPriceOffset(priceOffset + 0.1);
            } else if (code == KeyCode.PAGE_DOWN) {
                setPriceOffset(priceOffset - 0.1);
            } else if (code == KeyCode.HOME) {
                setStartIndex(0);
            } else if (code == KeyCode.END) {
                scrollToLatest();
            } else {
                return;
            }
            event.consume();
        });
    }

    /** 한 캔들이 차지하는 가로 폭. */
    private double slotWidth() {
        double plotRight = canvas.getWidth() - RIGHT;
        return visibleCount <= 0 ? 0 : (plotRight - LEFT) / visibleCount;
    }

    private void panBy(double candles) {
        // 방향키로 옮길 때는 캔들 경계에 맞춰 딱 떨어지게 한다.
        setStartIndex(Math.round(startIndex) + candles);
    }

    /**
     * 확대하거나 축소한다.
     *
     * @param anchorX 이 x 좌표 아래 캔들을 제자리에 유지한다. {@link Double#NaN} 이면
     *                화면 가운데를 기준으로 삼는다
     */
    private void zoomAt(boolean zoomIn, double anchorX) {
        int previousCount = visibleCount;
        int updated = zoomIn ? visibleCount - ZOOM_STEP : visibleCount + ZOOM_STEP;
        visibleCount = Math.max(MIN_VISIBLE, Math.min(points.size(), updated));
        if (visibleCount == previousCount) {
            // 더 넓히려 했는데 가진 캔들이 그것뿐이라 넓어지지 않았다. 끌지 않아도 자료가
            // 모자란 상황이므로 과거를 더 달라고 알린다. 축소만 계속하는 사용자는 왼쪽
            // 끝에 닿을 일이 없어, 여기서 알리지 않으면 영영 요청이 나가지 않는다.
            if (!zoomIn) {
                requestOlderIfNearStart();
            }
            return;
        }

        // 기준점이 가리키던 캔들이 확대 뒤에도 같은 자리에 오도록 시작 위치를 민다.
        double plotRight = canvas.getWidth() - RIGHT;
        double relative = 0.5;
        if (!Double.isNaN(anchorX) && plotRight > LEFT) {
            relative = Math.max(0, Math.min(1, (anchorX - LEFT) / (plotRight - LEFT)));
        }
        double anchorCandle = startIndex + relative * previousCount;
        setStartIndex(anchorCandle - relative * visibleCount);
        // 시작 위치가 그대로여도 보이는 개수가 달라졌다. setStartIndex 는 위치가 바뀌지
        // 않으면 알리지 않으므로, 축소로 화면이 넓어진 사실을 여기서 알린다.
        notifyViewportChanged();
    }

    /** 최신 캔들 오른쪽에 기본 미래 여백을 둔 자리로 되돌린다. */
    public void scrollToLatest() {
        setStartIndex(latestStart());
    }

    /** 도구 모음의 확대 단추가 휠·Ctrl+위쪽 키와 같은 동작을 호출한다. */
    public void zoomIn() {
        zoomAt(true, Double.NaN);
    }

    /** 도구 모음의 축소 단추가 휠·Ctrl+아래쪽 키와 같은 동작을 호출한다. */
    public void zoomOut() {
        zoomAt(false, Double.NaN);
    }

    /**
     * 가로 구간과 가격축을 처음 상태로 맞추고 최신 캔들로 돌아간다.
     * 가격축만 두 번 눌러 되돌리는 마우스 동작을 키보드와 단추에서도 쓸 수 있게 한다.
     */
    public void resetView() {
        visibleCount = Math.min(30, points.size());
        priceScale = 1.0;
        priceOffset = 0;
        startIndex = latestStart();
        crossX = -1;
        crossY = -1;
        draw();
        notifyViewportChanged();
    }

    /** 지금 최신 구간을 보고 있는지 여부. 화면이 "최신으로" 단추를 감출지 판단한다. */
    public boolean isAtLatest() {
        return startIndex >= dataFlushStart() - 1e-6;
    }

    /** 시작 위치를 자료 범위 안에 가둔다. */
    private void setStartIndex(double index) {
        double maxStart = maximumStart();
        double clamped = Math.max(0, Math.min(maxStart, index));
        if (Math.abs(clamped - startIndex) < 1e-6) {
            return;
        }
        boolean movedWholeCandle = (long) Math.floor(clamped) != (long) Math.floor(startIndex);
        startIndex = clamped;
        draw();
        // 접근성 요약은 캔들이 실제로 바뀔 때만 갱신한다. 픽셀마다 다시 읽으면
        // 스크린리더가 따라오지 못한다.
        if (movedWholeCandle || isAtLatest()) {
            notifyViewportChanged();
        }
    }

    /** 최신 봉이 오른쪽 끝에 딱 붙는 시작 위치. */
    private double dataFlushStart() {
        return Math.max(0, points.size() - visibleCount);
    }

    /** 가진 캔들을 전부 보는 중에는 실제 값을 밀어내며 빈칸을 만들지 않는다. */
    private int maximumFutureSlots() {
        if (points.size() <= visibleCount) return 0;
        return Math.min(MAX_FUTURE_SLOTS, Math.max(1, visibleCount - 1));
    }

    private double latestStart() {
        return dataFlushStart() + Math.min(DEFAULT_FUTURE_SLOTS, maximumFutureSlots());
    }

    private double maximumStart() {
        return dataFlushStart() + maximumFutureSlots();
    }

    /** 보이는 구간이 바뀔 때 화면에 알린다. */
    private void notifyViewportChanged() {
        setAccessibleText(buildAccessibleSummary());
        if (viewportListener != null) {
            viewportListener.run();
        }
        requestOlderIfNearStart();
    }

    /**
     * 왼쪽 끝에 가까워지면 과거를 더 달라고 알린다.
     *
     * <p>끝에 정확히 닿은 뒤 요청하면 데이터가 오는 동안 빈 구간이 보인다. 한 화면 분량
     * 남았을 때 미리 알려, 사용자가 끝에 닿을 때쯤에는 이미 이어져 있게 한다.
     *
     * <p>겹친 요청을 막는 일은 요청을 받는 쪽이 한다. 여기서는 경계에 들어왔다는 사실만
     * 알린다.
     */
    private void requestOlderIfNearStart() {
        if (olderRequest == null || points.isEmpty()) {
            return;
        }
        // 왼쪽 끝이 한 화면 안으로 들어왔을 때만 받는다.
        //
        // "전부 펼쳐 보고 있으면" 을 조건에 넣었다가 요청이 끝없이 되풀이됐다. 받아 온
        // 만큼 화면을 넓히면 다시 전부 보는 상태가 되어 조건이 계속 참으로 남기 때문이다.
        // 축소해서 더 넓힐 수 없는 경우는 zoomAt 이 그때 한 번만 알린다.
        if (startIndex <= visibleCount) {
            olderRequest.run();
        }
    }

    /**
     * 과거 구간이 더 필요할 때 실행할 동작.
     *
     * <p>화면이 이 동작에서 조회를 시작하고, 받아 온 지점으로 {@link #setPoints} 를 부른다.
     */
    public void setOlderDataRequest(Runnable request) {
        this.olderRequest = request;
    }

    /** 보이는 구간이 바뀔 때 실행할 동작. "최신으로" 단추를 켜고 끄는 데 쓴다. */
    public void setViewportListener(Runnable listener) {
        this.viewportListener = listener;
    }

    /**
     * 거래량 칸을 켜고 끈다.
     *
     * <p>끈 값도 사라지지 않는다. 같은 값이 "접근 가능한 표" 의 거래량 칸에 그대로 있다.
     * 좁은 칸에서 끄고 크게 보기 창에서 켜는 식으로 쓴다.
     */
    /** 지금 그리고 있는 값. 크게 보기 창이 같은 값으로 다시 그린다. */
    public List<PricePoint> points() {
        return List.copyOf(points);
    }

    /**
     * 현재 봉 주기를 알려 주어 X축을 실제 차트 관례에 맞게 표시한다.
     * 분봉은 시:분, 일·주봉은 월/일, 월봉은 연/월을 쓴다.
     */
    public void setInterval(CandleInterval value) {
        CandleInterval updated = Objects.requireNonNull(value, "value");
        if (updated == interval) {
            return;
        }
        interval = updated;
        setAccessibleText(buildAccessibleSummary());
        draw();
    }

    public void setShowVolume(boolean value) {
        showVolume = value;
        draw();
    }

    /**
     * 세로 축척을 정한다. 1이면 보이는 구간에 꼭 맞춘다.
     *
     * <p>너무 늘리거나 눌러 값이 화면을 벗어나지 않게 범위를 묶는다. 축이 뒤집히면
     * 위아래가 바뀌어 오르는 것이 내리는 것으로 보인다.
     */
    public void setPriceScale(double value) {
        double clamped = Math.max(MIN_PRICE_SCALE, Math.min(MAX_PRICE_SCALE, value));
        if (clamped == priceScale) {
            return;
        }
        priceScale = clamped;
        if (priceScale == 1.0) {
            // 자동 맞춤으로 돌아오면 모든 캔들이 다시 들어온다. 옮겨 둘 이유가 없다.
            priceOffset = 0;
        }
        draw();
    }

    /**
     * 가격 창을 위아래로 옮긴다. 1이면 보이는 값 폭만큼 통째로 옮긴 것이다.
     *
     * <p>축척을 손으로 좁혀 잘려 나간 캔들을 보러 갈 때 쓴다. 자동 맞춤에서는 잘릴
     * 것이 없어 아무 일도 하지 않는다.
     */
    public void setPriceOffset(double value) {
        if (priceScale == 1.0) {
            return;
        }
        double clamped = Math.max(-MAX_PRICE_OFFSET, Math.min(MAX_PRICE_OFFSET, value));
        if (clamped == priceOffset) {
            return;
        }
        priceOffset = clamped;
        draw();
    }

    /** 가격 창을 옮긴 정도. 0이면 축척이 정한 자리 그대로다. */
    public double priceOffset() {
        return priceOffset;
    }

    /** 가격 칸의 높이. 세로로 끈 거리를 값 폭의 비율로 옮길 때 쓴다. */
    private double pricePanelHeight() {
        double usable = Math.max(120, canvas.getHeight() - TOP - BOTTOM);
        return usable * (showVolume || showRsi || showMacd ? 0.72 : 1.0);
    }

    public void setShowMovingAverages(boolean value) {
        showMa = value;
        draw();
    }

    public void setShowBollinger(boolean value) {
        showBollinger = value;
        draw();
    }

    public void setShowRsi(boolean value) {
        showRsi = value;
        draw();
    }

    public void setShowMacd(boolean value) {
        showMacd = value;
        draw();
    }

    public void setPoints(List<PricePoint> updatedPoints) {
        if (updatedPoints == null || updatedPoints.isEmpty()) {
            throw new IllegalArgumentException("차트 데이터는 한 건 이상이어야 합니다.");
        }
        boolean wasAtLatest = isAtLatest();
        // 실시간 값이 와도 사용자가 확보한 오른쪽 빈 공간을 유지한다.
        double futureSlots = Math.max(0, startIndex - dataFlushStart());
        int previousCount = visibleCount;
        // 가진 캔들을 전부 펼쳐 보던 중이었는지. 이때는 시작이 0이라 "최신에 붙어 있음" 과
        // 구분이 안 되므로 따로 기억해 둔다. 과거가 붙은 뒤 최신 구간으로 좁혀 버리면
        // 넓게 보려고 축소한 사용자의 화면이 갑자기 되돌아간다.
        //
        // 다만 보이는 개수를 늘어난 전체로 바꾸지는 않는다. 그렇게 하면 받아 올 때마다
        // 화면이 넓어지고, 넓어진 만큼 또 받아 오는 고리가 생겨 수천 개가 한 화면에 눌린다.
        // 배율은 그대로 두고 위치만 가장 과거로 옮겨, 새로 받은 구간이 바로 보이게 한다.
        boolean wasShowingEverything = !points.isEmpty() && visibleCount >= points.size();
        // 과거가 앞에 붙으면 모든 위치가 그만큼 밀린다. 보고 있던 캔들을 기억해 두었다가
        // 새 목록에서 다시 찾아 같은 자리를 보게 한다. 기억하지 않으면 과거를 받아올 때마다
        // 화면이 훌쩍 건너뛴다.
        PricePoint anchor = null;
        double anchorFraction = 0;
        if ((!wasAtLatest || wasShowingEverything) && !points.isEmpty()) {
            int anchorIndex = (int) Math.floor(startIndex);
            if (anchorIndex >= 0 && anchorIndex < points.size()) {
                anchor = points.get(anchorIndex);
                anchorFraction = startIndex - anchorIndex;
            }
        }
        points = List.copyOf(updatedPoints);
        // 보던 배율을 지킨다. 실시간 값이 올 때마다 30개로 되돌리면, 넓게 펼쳐 놓고
        // 보던 사람은 틱이 한 번 올 때마다 처음부터 다시 확대해야 한다.
        visibleCount = previousCount > 0
                ? Math.max(MIN_VISIBLE, Math.min(points.size(), previousCount))
                : Math.min(30, points.size());
        // 최신을 보고 있었다면 새 캔들을 따라가고, 과거를 살펴보던 중이었다면
        // 보던 자리를 지킨다. 읽던 구간이 갑자기 끌려가면 다시 찾아야 한다.
        double maxStart = maximumStart();
        if (wasAtLatest && !wasShowingEverything) {
            startIndex = Math.min(maxStart, dataFlushStart() + futureSlots);
        } else {
            // 보고 있던 캔들이 새 목록에서 옮겨 간 자리로 따라간다. 전부 펼쳐 보던
            // 중이었어도 마찬가지다. 0으로 되돌리면 왼쪽 끝에 붙어 있게 되어, 그 자리가
            // 다시 "더 받아야 하는 위치" 로 읽혀 요청이 되풀이된다.
            double restored = startIndex;
            if (anchor != null) {
                int moved = points.indexOf(anchor);
                if (moved >= 0) {
                    restored = moved + anchorFraction;
                }
            }
            startIndex = Math.max(0, Math.min(restored, maxStart));
        }
        crossX = -1; crossY = -1;
        setAccessibleText(buildAccessibleSummary());
        draw();
        notifyViewportChanged();
        if (pointsListener != null) {
            pointsListener.accept(points);
        }
    }

    /**
     * 이 차트가 새 값을 받을 때 함께 받을 곳을 정한다. {@code null} 이면 뗀다.
     *
     * <p>창을 닫을 때 반드시 떼야 한다. 남겨 두면 닫힌 창을 계속 그리려 한다.
     */
    public void setPointsListener(Consumer<List<PricePoint>> listener) {
        this.pointsListener = listener;
    }

    @Override
    protected void layoutChildren() {
        double width = Math.max(1, getWidth());
        double height = Math.max(1, getHeight());
        if (canvas.getWidth() != width || canvas.getHeight() != height) {
            canvas.setWidth(width);
            canvas.setHeight(height);
        }
        draw();
    }

    private void draw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width < 200 || height < 180) return;

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#0b1220"));
        g.fillRect(0, 0, width, height);
        g.setFont(Font.font("Noto Sans KR", 11));
        g.setTextBaseline(VPos.CENTER);

        // 정수 부분으로 자르고, 소수 부분은 그릴 때 통째로 옆으로 민다. 잘라내기만 하면
        // 캔들이 한 칸씩 튀지만, 밀어 주면 캔들·격자·눈금이 함께 손을 따라 흐른다.
        // 양옆으로 한 칸씩 더 그려, 밀린 자리에 빈 곳이 생기지 않게 한다.
        double viewportStart = Math.max(0, Math.min(startIndex, maximumStart()));
        int start = (int) Math.floor(viewportStart);
        double fraction = viewportStart - start;
        int from = Math.max(0, start - 1);
        int to = Math.min(points.size(), start + visibleCount + 2);
        List<PricePoint> visible = points.subList(from, to);
        // 잘라낸 시작점이 앞으로 밀린 만큼 되돌려 준다.
        double shiftCandles = fraction + (start - from);
        double plotRight = width - RIGHT;
        double usableHeight = Math.max(120, height - TOP - BOTTOM);
        // 보조 칸을 안 쓰면 그 자리를 가격 차트가 가져간다. 빈 띠를 남겨 둘 이유가 없다.
        boolean indicators = showVolume || showRsi || showMacd;
        double mainBottom = TOP + usableHeight * (indicators ? 0.72 : 1.0);
        double indicatorTop = mainBottom + 14;
        double indicatorBottom = height - BOTTOM;

        double min = visible.stream().map(PricePoint::low).mapToDouble(BigDecimal::doubleValue).min().orElse(0);
        double max = visible.stream().map(PricePoint::high).mapToDouble(BigDecimal::doubleValue).max().orElse(1);
        double padding = Math.max(1, (max - min) * 0.08);
        min -= padding;
        max += padding;
        if (priceScale != 1.0) {
            double middle = (min + max) / 2;
            double half = (max - min) / 2 * priceScale;
            min = middle - half;
            max = middle + half;
        }
        if (priceOffset != 0) {
            // 잘려 나간 쪽을 보러 창을 통째로 올리고 내린다. 값 폭은 그대로다.
            double shift = (max - min) * priceOffset;
            min += shift;
            max += shift;
        }

        drawGrid(g, plotRight, mainBottom, indicatorTop, indicatorBottom, min, max);

        // 여유분까지 나누면 캔들이 좁아진다. 폭은 실제로 보여 줄 개수로 정한다.
        double slot = (plotRight - LEFT) / visibleCount;
        shiftPx = shiftCandles * slot;
        double candleWidth = Math.max(3, Math.min(14, slot * 0.62));
        long maxVolume = visible.stream().mapToLong(PricePoint::volume).max().orElse(1);

        // 세로 축척을 손으로 바꾸면 값이 가격 칸을 넘친다. 실제 거래 프로그램처럼
        // 넘친 부분은 잘라 낸다. 안 자르면 꼬리가 위쪽 시세 표시줄과 아래 거래량
        // 칸까지 뚫고 나가 다른 값 위에 겹쳐 그려진다.
        g.save();
        g.beginPath();
        g.rect(LEFT, TOP, plotRight - LEFT, mainBottom - TOP);
        g.clip();

        for (int index = 0; index < visible.size(); index++) {
            PricePoint point = visible.get(index);
            double x = LEFT + slot * index + slot / 2 - shiftPx;
            if (x < LEFT - slot || x > plotRight + slot) {
                continue;
            }
            double open = y(point.open().doubleValue(), min, max, TOP, mainBottom);
            double close = y(point.close().doubleValue(), min, max, TOP, mainBottom);
            double high = y(point.high().doubleValue(), min, max, TOP, mainBottom);
            double low = y(point.low().doubleValue(), min, max, TOP, mainBottom);
            boolean up = point.close().compareTo(point.open()) >= 0;
            Color color = Color.web(up ? "#ff4d4f" : "#3b82f6");
            g.setStroke(color);
            g.setFill(color);
            g.setLineWidth(1.2);
            g.strokeLine(x, high, x, low);
            double bodyTop = Math.min(open, close);
            double bodyHeight = Math.max(2, Math.abs(close - open));
            g.fillRect(x - candleWidth / 2, bodyTop, candleWidth, bodyHeight);
        }

        if (showMa) {
            drawAverage(g, visible, 5, Color.web("#c084fc"), min, max, mainBottom, slot);
            drawAverage(g, visible, 20, Color.web("#fbbf24"), min, max, mainBottom, slot);
        }
        if (showBollinger) drawBollinger(g, visible, min, max, mainBottom, slot);
        g.restore();

        // 거래량은 아래 칸의 몫이라 가격 칸의 자르기 밖에서 그린다. 세로 축척은
        // 가격에만 걸리므로 거래량 막대는 넘칠 일이 없다.
        if (showVolume) {
            for (int index = 0; index < visible.size(); index++) {
                PricePoint point = visible.get(index);
                double x = LEFT + slot * index + slot / 2 - shiftPx;
                if (x < LEFT - slot || x > plotRight + slot) {
                    continue;
                }
                boolean up = point.close().compareTo(point.open()) >= 0;
                g.setFill(Color.web(up ? "#ff4d4f" : "#3b82f6"));
                double volumeHeight = (indicatorBottom - indicatorTop) * point.volume() / Math.max(1d, maxVolume);
                g.setGlobalAlpha(0.52);
                g.fillRect(x - candleWidth / 2, indicatorBottom - volumeHeight, candleWidth, volumeHeight);
                g.setGlobalAlpha(1);
            }
        }
        if (showRsi) drawRsi(g, visible, indicatorTop, indicatorBottom, slot);
        if (showMacd) drawMacd(g, visible, indicatorTop, indicatorBottom, slot);

        double previousClose = points.get(Math.max(0, start - 1)).close().doubleValue();
        drawPriceLine(g, previousClose, min, max, mainBottom, width, "전일", Color.web("#64748b"));
        drawCurrentPrice(g, visible.get(visible.size() - 1), min, max, mainBottom, plotRight);
        drawTimeAxis(g, visible, slot, plotRight, height);
        drawOhlcHeader(g, visible.get(visible.size() - 1));
        drawLegend(g, width);
        drawCrosshair(g, visible, min, max, mainBottom, slot, width);
    }

    private void drawGrid(GraphicsContext g, double plotRight, double mainBottom,
                          double indicatorTop, double indicatorBottom, double min, double max) {
        g.setStroke(Color.web("#243247"));
        g.setFill(Color.web("#94a3b8"));
        g.setLineWidth(1);
        for (int i = 0; i <= 5; i++) {
            double ratio = i / 5d;
            double y = TOP + (mainBottom - TOP) * ratio;
            double price = max - (max - min) * ratio;
            g.strokeLine(LEFT, y, plotRight, y);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(String.format("%,.0f", price), plotRight + 8, y);
        }
        for (int i = 0; i <= 6; i++) {
            double x = LEFT + (plotRight - LEFT) * i / 6d;
            g.strokeLine(x, TOP, x, indicatorBottom);
        }
        g.setStroke(Color.web("#334155"));
        g.strokeLine(LEFT, indicatorTop - 7, plotRight, indicatorTop - 7);
        g.setFill(Color.web("#64748b"));
        g.setTextAlign(TextAlignment.LEFT);
        if (showVolume) g.fillText("거래량", LEFT + 4, indicatorTop + 4);
    }

    private void drawAverage(GraphicsContext g, List<PricePoint> visible, int period, Color color,
                             double min, double max, double mainBottom, double slot) {
        g.setStroke(color);
        g.setLineWidth(1.8);
        boolean started = false;
        for (int i = 0; i < visible.size(); i++) {
            if (i + 1 < period) continue;
            double average = visible.subList(i + 1 - period, i + 1).stream()
                    .map(PricePoint::close).mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            double x = LEFT + slot * i + slot / 2 - shiftPx;
            double y = y(average, min, max, TOP, mainBottom);
            if (!started) {
                g.beginPath();
                g.moveTo(x, y);
                started = true;
            } else g.lineTo(x, y);
        }
        if (started) g.stroke();
    }

    private void drawBollinger(GraphicsContext g, List<PricePoint> visible, double min, double max,
                               double mainBottom, double slot) {
        drawBand(g, visible, min, max, mainBottom, slot, true);
        drawBand(g, visible, min, max, mainBottom, slot, false);
    }

    private void drawBand(GraphicsContext g, List<PricePoint> visible, double min, double max,
                          double mainBottom, double slot, boolean upper) {
        g.setStroke(Color.web("#38bdf8"));
        g.setLineWidth(1.1);
        boolean started = false;
        for (int i = 0; i < visible.size(); i++) {
            int from = Math.max(0, i - 19);
            List<PricePoint> window = visible.subList(from, i + 1);
            double mean = window.stream().map(PricePoint::close).mapToDouble(BigDecimal::doubleValue).average().orElse(0);
            double variance = window.stream().map(PricePoint::close).mapToDouble(BigDecimal::doubleValue)
                    .map(value -> Math.pow(value - mean, 2)).average().orElse(0);
            double value = mean + (upper ? 2 : -2) * Math.sqrt(variance);
            double x = LEFT + slot * i + slot / 2 - shiftPx;
            double y = y(value, min, max, TOP, mainBottom);
            if (!started) { g.beginPath(); g.moveTo(x, y); started = true; } else g.lineTo(x, y);
        }
        if (started) g.stroke();
    }

    private void drawRsi(GraphicsContext g, List<PricePoint> visible, double top, double bottom, double slot) {
        g.setStroke(Color.web("#c084fc"));
        g.setLineWidth(1.4);
        g.beginPath();
        for (int i = 0; i < visible.size(); i++) {
            double rsi = rsiAt(visible, i, 14);
            double x = LEFT + slot * i + slot / 2 - shiftPx;
            double y = bottom - (bottom - top) * rsi / 100d;
            if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
        }
        g.stroke();
        g.setFill(Color.web("#c084fc"));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("RSI", LEFT + 4, top + 8);
    }

    private void drawMacd(GraphicsContext g, List<PricePoint> visible, double top, double bottom, double slot) {
        double[] values = new double[visible.size()];
        double maxAbs = 1;
        for (int i = 0; i < visible.size(); i++) {
            values[i] = movingAverage(visible, i, 12) - movingAverage(visible, i, 26);
            maxAbs = Math.max(maxAbs, Math.abs(values[i]));
        }
        double middle = (top + bottom) / 2;
        for (int i = 0; i < values.length; i++) {
            double x = LEFT + slot * i + slot * 0.2 - shiftPx;
            double height = (bottom - top) * 0.42 * values[i] / maxAbs;
            g.setFill(Color.web(values[i] >= 0 ? "#ff4d4f" : "#3b82f6", 0.62));
            g.fillRect(x, middle - Math.max(0, height), Math.max(2, slot * 0.6), Math.abs(height));
        }
        g.setFill(Color.web("#94a3b8"));
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("MACD", canvas.getWidth() - RIGHT - 4, top + 8);
    }

    private void drawPriceLine(GraphicsContext g, double value, double min, double max, double mainBottom,
                               double width, String name, Color color) {
        if (value < min || value > max) return;
        double y = y(value, min, max, TOP, mainBottom);
        g.setStroke(color);
        g.setLineDashes(5, 4);
        g.strokeLine(LEFT, y, width - RIGHT, y);
        g.setLineDashes();
        g.setFill(color);
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText(name, LEFT + 5, y - 8);
    }

    private void drawCurrentPrice(GraphicsContext g, PricePoint latest, double min, double max,
                                  double mainBottom, double plotRight) {
        double current = latest.close().doubleValue();
        if (current < min || current > max) return;
        boolean up = latest.close().compareTo(latest.open()) >= 0;
        Color color = Color.web(up ? "#ff4d4f" : "#3b82f6");
        double y = y(current, min, max, TOP, mainBottom);
        g.setStroke(color);
        g.setLineDashes(4, 3);
        g.strokeLine(LEFT, y, plotRight, y);
        g.setLineDashes();
        g.setFill(color);
        g.fillRoundRect(plotRight + 4, y - 10, RIGHT - 9, 20, 4, 4);
        g.setFill(Color.WHITE);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(String.format("%,.0f", current), plotRight + (RIGHT - 1) / 2, y);
    }

    private void drawTimeAxis(GraphicsContext g, List<PricePoint> visible, double slot,
                              double plotRight, double height) {
        int step = Math.max(1, (int) Math.ceil(visible.size() / 6d));
        g.setFill(Color.web("#94a3b8"));
        g.setTextAlign(TextAlignment.CENTER);
        for (int index = 0; index < visible.size(); index += step) {
            PricePoint point = visible.get(index);
            double x = LEFT + slot * index + slot / 2 - shiftPx;
            if (x < LEFT - slot || x > plotRight + slot) {
                continue;
            }
            g.fillText(axisLabel(point), x, height - 10);
        }
    }

    private void drawOhlcHeader(GraphicsContext g, PricePoint point) {
        boolean up = point.close().compareTo(point.open()) >= 0;
        g.setFill(Color.web(up ? "#ff6b6d" : "#60a5fa"));
        g.setTextAlign(TextAlignment.LEFT);
        String text = detailTime(point) + "  O " + formatPrice(point.open())
                + "  H " + formatPrice(point.high())
                + "  L " + formatPrice(point.low())
                + "  C " + formatPrice(point.close())
                + "  V " + String.format("%,d", point.volume());
        g.fillText(text, LEFT, 18);
    }

    private void drawLegend(GraphicsContext g, double width) {
        g.setFill(Color.web("#94a3b8"));
        g.setTextAlign(TextAlignment.RIGHT);
        StringBuilder legend = new StringBuilder("거래량");
        if (showMa) legend.append(" · MA5 · MA20");
        if (showBollinger) legend.append(" · Bollinger");
        if (showRsi) legend.append(" · RSI");
        if (showMacd) legend.append(" · MACD");
        g.fillText(legend.toString(), width - RIGHT, 32);
    }

    private void drawCrosshair(GraphicsContext g, List<PricePoint> visible, double min, double max,
                               double mainBottom, double slot, double width) {
        if (crossX < LEFT || crossX > width - RIGHT || crossY < TOP || crossY > mainBottom) return;
        double plotRight = width - RIGHT;
        g.setStroke(Color.web("#cbd5e1"));
        g.setLineDashes(3, 3);
        g.strokeLine(crossX, TOP, crossX, mainBottom);
        g.strokeLine(LEFT, crossY, width - RIGHT, crossY);
        g.setLineDashes();

        // 그릴 때 민 만큼 되돌려야 커서 아래 캔들을 정확히 집는다. 미래 빈칸에서는
        // 마지막 캔들의 OHLC를 반복해 보여 주지 않는다 — 아직 생기지 않은 봉이다.
        int index = (int) ((crossX - LEFT + shiftPx) / slot);
        double price = max - (crossY - TOP) / (mainBottom - TOP) * (max - min);
        if (index >= 0 && index < visible.size()) {
            PricePoint point = visible.get(index);
            String label = detailTime(point) + "  O " + formatPrice(point.open())
                    + "  H " + formatPrice(point.high()) + "  L " + formatPrice(point.low())
                    + "  C " + formatPrice(point.close()) + "  V " + String.format("%,d", point.volume());
            g.setFont(Font.font("Noto Sans KR", 12));
            g.setTextAlign(TextAlignment.LEFT);
            double boxWidth = Math.min(plotRight - LEFT, 440);
            double boxX = Math.min(Math.max(LEFT, crossX + 8), plotRight - boxWidth);
            g.setFill(Color.web("#1e293b", 0.96));
            g.fillRoundRect(boxX, TOP + 6, boxWidth, 28, 7, 7);
            g.setFill(Color.WHITE);
            g.fillText(label, boxX + 9, TOP + 20);
        }

        g.setFill(Color.web("#334155"));
        g.fillRoundRect(plotRight + 4, crossY - 10, RIGHT - 9, 20, 4, 4);
        g.setFill(Color.WHITE);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(String.format("%,.0f", price), plotRight + (RIGHT - 1) / 2, crossY);
    }

    private double rsiAt(List<PricePoint> values, int index, int period) {
        int from = Math.max(1, index - period + 1);
        double gains = 0;
        double losses = 0;
        for (int i = from; i <= index; i++) {
            double change = values.get(i).close().subtract(values.get(i - 1).close()).doubleValue();
            if (change >= 0) gains += change; else losses -= change;
        }
        if (gains + losses == 0) return 50;
        return 100 * gains / (gains + losses);
    }

    private double movingAverage(List<PricePoint> values, int index, int period) {
        int from = Math.max(0, index - period + 1);
        return values.subList(from, index + 1).stream().map(PricePoint::close)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0);
    }

    private double y(double value, double min, double max, double top, double bottom) {
        return bottom - (value - min) / Math.max(0.0001, max - min) * (bottom - top);
    }

    /**
     * 지금 보이는 구간을 설명한다.
     *
     * <p>전체 자료가 아니라 보이는 구간을 기준으로 삼는다. 그렇지 않으면 사용자가
     * 좌우로 옮겨도 읽히는 내용이 그대로여서, 옮겼는지 알 수 없다.
     */
    private String buildAccessibleSummary() {
        int start = (int) Math.floor(Math.max(0, Math.min(startIndex, maximumStart())));
        start = Math.max(0, Math.min(start, points.size() - 1));
        List<PricePoint> visible = points.subList(start, Math.min(points.size(), start + visibleCount));
        PricePoint first = visible.get(0);
        PricePoint last = visible.get(visible.size() - 1);
        BigDecimal change = last.close().subtract(first.close());
        String direction = change.signum() > 0 ? "상승" : change.signum() < 0 ? "하락" : "보합";
        String position = isAtLatest() ? "최신 구간" : "과거 구간";
        int futureSlots = Math.max(0, start + visibleCount - points.size());
        String future = futureSlots == 0 ? "" : " 오른쪽 미래 빈 공간 " + futureSlots + "칸,";
        return "캔들 차트. " + interval.displayName() + ", " + position + future + " "
                + visible.size() + "개, " + detailTime(first) + "부터 "
                + detailTime(last) + "까지, 시작 종가 " + format(first.close()) + ", 마지막 종가 "
                + format(last.close()) + ", " + direction + " " + format(change.abs()) + ".";
    }

    private String axisLabel(PricePoint point) {
        if (interval.isIntraday()) {
            return point.timestamp().format(HOUR_MINUTE);
        }
        if (interval == CandleInterval.MONTH) {
            return point.timestamp().format(YEAR_MONTH);
        }
        return point.timestamp().format(MONTH_DAY);
    }

    private String detailTime(PricePoint point) {
        if (interval.isIntraday()) {
            return point.timestamp().format(DATE_TIME);
        }
        return point.date().toString();
    }

    private String format(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatPrice(BigDecimal value) {
        return String.format("%,.0f", value.doubleValue());
    }
}
