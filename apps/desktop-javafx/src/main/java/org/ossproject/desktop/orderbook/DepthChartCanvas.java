package org.ossproject.desktop.orderbook;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.ossproject.finance.model.orderbook.DepthChartView;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * 누적 호가 깊이 그래프.
 *
 * <p>좌표 계산은 {@link DepthChartView} 가 이미 끝냈다. 점마다 0.0~1.0 으로 정규화된
 * 가로·세로 비율이 들어 있어서 여기서는 폭과 높이를 곱하기만 한다. 축을 고정하고 언제
 * 다시 잡을지는 도메인이 정하므로 화면은 그 규칙을 알 필요가 없다.
 *
 * <p><b>가격 눈금은 축에 고정한다.</b> 눈금을 데이터 점 옆에 붙이면 물량이 오갈 때마다
 * 숫자가 함께 흔들려, 화면을 확대해 보는 사용자가 보던 자리를 매번 다시 찾아야 한다.
 * 보이는 구간에서 눈금을 계산해 왼쪽에 고정하고 막대만 움직인다.
 *
 * <p><b>확대와 이동은 화면만의 관심사다.</b> 도메인이 준 값은 그대로 두고, 그중 어느
 * 구간을 볼지만 여기서 정한다. 잔량이 촘촘한 구간을 크게 키워 보는 것은 저시력
 * 사용자에게 특히 중요하다.
 *
 * <p>같은 값을 호가 표가 글자로 보여 주고 스크린리더는 그쪽을 읽는다. 다만 확대·이동은
 * 마우스로만 되면 키보드 사용자가 쓸 수 없으므로, 방향키와 더하기·빼기로도 같은 조작을
 * 할 수 있게 하고 현재 보이는 구간을 접근성 설명에 적어 둔다.
 */
public final class DepthChartCanvas extends Region {

    private static final double LEFT = 70;
    private static final double RIGHT = 16;
    private static final double TOP = 16;
    private static final double BOTTOM = 24;
    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.KOREA);
    /** 세로축에 표시할 가격 눈금 수. 홀수라 가운데 눈금이 중앙에 온다. */
    private static final int PRICE_TICKS = 7;

    /** 확대 배율 범위. 1이면 전체가 보이고, 커질수록 좁은 구간을 크게 본다. */
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 8.0;
    private static final double ZOOM_STEP = 1.25;
    /** 휠 한 칸이 옮기는 거리. 보이는 구간의 비율이라 확대해도 체감 속도가 같다. */
    private static final double PAN_STEP = 0.15;

    private static final Color ASK = Color.web("#c0392b");
    private static final Color BID = Color.web("#1f6fb2");
    private static final Color WALL = Color.web("#8e44ad");
    private static final Color AXIS = Color.web("#9aa4ad");
    private static final Color TEXT = Color.web("#3c4650");
    /** 눈금 안내선. 막대를 가리지 않도록 아주 옅게 둔다. */
    private static final Color GRID = Color.web("#e1e6ea");

    private final Canvas canvas = new Canvas();
    private DepthChartView view;

    /** 보이는 구간의 중심. 0이 저가 끝, 1이 고가 끝이다. */
    private double panCenter = 0.5;
    private double zoom = MIN_ZOOM;
    private double dragAnchorY = Double.NaN;
    private double dragAnchorCenter;

    public DepthChartCanvas() {
        getChildren().add(canvas);
        setMinHeight(240);
        setPrefHeight(320);
        setFocusTraversable(true);
        installInteractions();
        updateAccessibleDescription();
    }

    public void update(DepthChartView updated) {
        this.view = updated;
        requestLayout();
        updateAccessibleDescription();
    }

    /** 확대와 이동을 처음 상태로 되돌린다. */
    public void resetViewport() {
        zoom = MIN_ZOOM;
        panCenter = 0.5;
        requestLayout();
        updateAccessibleDescription();
    }

    // ------------------------------------------------------------------
    // 조작
    // ------------------------------------------------------------------

    private void installInteractions() {
        // 휠은 위아래 이동. 확대는 보조키를 함께 눌러야 해서, 표를 훑다 실수로
        // 배율이 바뀌는 일이 없다.
        setOnScroll(event -> {
            if (event.isControlDown() || event.isMetaDown() || event.isShiftDown()) {
                zoomBy(event.getDeltaY() > 0 ? ZOOM_STEP : 1 / ZOOM_STEP);
            } else {
                panBy(event.getDeltaY() > 0 ? -PAN_STEP : PAN_STEP);
            }
            event.consume();
        });

        setOnMousePressed(event -> {
            requestFocus();
            dragAnchorY = event.getY();
            dragAnchorCenter = panCenter;
            event.consume();
        });
        setOnMouseDragged(event -> {
            if (Double.isNaN(dragAnchorY)) {
                return;
            }
            double plotHeight = Math.max(1, getHeight() - TOP - BOTTOM);
            // 끌어올린 만큼 구간이 위로 간다. 손가락이 내용을 잡아 끄는 느낌이 되도록
            // 화면 이동 방향과 값 이동 방향을 맞춘다.
            double moved = (event.getY() - dragAnchorY) / plotHeight;
            setPanCenter(dragAnchorCenter + moved * visibleSpan());
            event.consume();
        });
        setOnMouseReleased(event -> dragAnchorY = Double.NaN);

        setOnKeyPressed(event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.UP) {
                panBy(-PAN_STEP);
            } else if (code == KeyCode.DOWN) {
                panBy(PAN_STEP);
            } else if (code == KeyCode.PLUS || code == KeyCode.EQUALS || code == KeyCode.ADD) {
                zoomBy(ZOOM_STEP);
            } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
                zoomBy(1 / ZOOM_STEP);
            } else if (code == KeyCode.HOME || code == KeyCode.DIGIT0) {
                resetViewport();
            } else {
                return;
            }
            event.consume();
        });
    }

    private void panBy(double deltaInVisibleSpan) {
        setPanCenter(panCenter + deltaInVisibleSpan * visibleSpan());
    }

    private void zoomBy(double factor) {
        zoom = clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
        // 배율이 낮아지면 보이는 구간이 넓어져 중심이 밖으로 밀릴 수 있다.
        setPanCenter(panCenter);
    }

    /** 보이는 구간이 축 밖으로 나가지 않도록 중심을 가둔다. */
    private void setPanCenter(double center) {
        double half = visibleSpan() / 2;
        panCenter = clamp(center, half, 1 - half);
        requestLayout();
        updateAccessibleDescription();
    }

    /** 전체 범위 중 지금 보이는 비율. */
    private double visibleSpan() {
        return Math.min(1.0, 1.0 / zoom);
    }

    private double viewportLow() {
        return panCenter - visibleSpan() / 2;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

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
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        gc.clearRect(0, 0, width, height);
        gc.setFont(Font.font("Noto Sans KR", 11));

        if (view == null || view.isEmpty()) {
            gc.setFill(TEXT);
            gc.fillText("표시할 호가가 없습니다.", LEFT, height / 2);
            return;
        }

        double plotWidth = Math.max(1, width - LEFT - RIGHT);
        double plotHeight = Math.max(1, height - TOP - BOTTOM);

        gc.setStroke(AXIS);
        gc.setLineWidth(1);
        gc.strokeLine(LEFT, TOP, LEFT, TOP + plotHeight);

        drawPriceAxis(gc, plotWidth, plotHeight);
        drawSide(gc, view.askPoints(), ASK, plotWidth, plotHeight);
        drawSide(gc, view.bidPoints(), BID, plotWidth, plotHeight);
        drawScale(gc, width, height);
    }

    /**
     * 정규화된 비율에 폭과 높이를 곱해 막대로 그린다.
     *
     * <p>보이는 구간 밖의 점은 건너뛴다. 잘린 막대를 억지로 그리면 길이가 잘못 읽힌다.
     */
    private void drawSide(GraphicsContext gc, List<DepthChartView.Plot> points,
                          Color color, double plotWidth, double plotHeight) {
        int visibleCount = countVisible(view.askPoints()) + countVisible(view.bidPoints());
        double barHeight = Math.max(2, plotHeight / Math.max(1, visibleCount) - 1);

        for (DepthChartView.Plot plot : points) {
            Double y = screenY(plot.priceRatio(), plotHeight);
            if (y == null) {
                continue;
            }
            double length = plot.depthRatio() * plotWidth;
            gc.setFill(plot.wall() ? WALL : color);
            gc.fillRect(LEFT + 1, y - barHeight / 2, Math.max(1, length), barHeight);
        }
    }

    private int countVisible(List<DepthChartView.Plot> points) {
        int count = 0;
        for (DepthChartView.Plot plot : points) {
            if (isVisible(plot.priceRatio())) {
                count++;
            }
        }
        return count;
    }

    private boolean isVisible(double priceRatio) {
        double low = viewportLow();
        return priceRatio >= low && priceRatio <= low + visibleSpan();
    }

    /** 축 비율을 화면 좌표로 옮긴다. 보이는 구간 밖이면 {@code null}. */
    private Double screenY(double priceRatio, double plotHeight) {
        if (!isVisible(priceRatio)) {
            return null;
        }
        double relative = (priceRatio - viewportLow()) / visibleSpan();
        return TOP + (1.0 - relative) * plotHeight;
    }

    /**
     * 보이는 구간을 균등 분할해 가격 눈금을 왼쪽에 고정한다.
     *
     * <p>눈금 값은 축 범위와 보이는 구간으로만 정해진다. 물량이 아무리 오가도 눈금은
     * 제자리에 머물고, 사용자가 이동하거나 확대할 때만 바뀐다.
     */
    private void drawPriceAxis(GraphicsContext gc, double plotWidth, double plotHeight) {
        BigDecimal high = view.highestPrice();
        BigDecimal low = view.lowestPrice();
        if (high == null || low == null) {
            return;
        }
        BigDecimal span = high.subtract(low);
        if (span.signum() <= 0) {
            return;
        }

        for (int index = 0; index < PRICE_TICKS; index++) {
            double relative = (double) index / (PRICE_TICKS - 1);
            double axisRatio = viewportLow() + relative * visibleSpan();
            BigDecimal price = low.add(span.multiply(BigDecimal.valueOf(axisRatio)));
            double y = TOP + (1.0 - relative) * plotHeight;

            // 옅은 안내선으로 눈금 높이를 알려 준다. 막대를 가리지 않도록 흐리게 그린다.
            gc.setStroke(GRID);
            gc.setLineWidth(1);
            gc.strokeLine(LEFT + 1, y, LEFT + plotWidth, y);

            gc.setFill(TEXT);
            gc.fillText(NUMBERS.format(price), 6, y + 4);
        }
    }

    private void drawScale(GraphicsContext gc, double width, double height) {
        gc.setFill(TEXT);
        String scale = "가로축 기준 누적 " + NUMBERS.format(view.depthScale()) + "주";
        if (zoom > MIN_ZOOM) {
            scale += " · " + Math.round(zoom * 10) / 10.0 + "배 확대";
        }
        gc.fillText(scale, LEFT, height - 8);
        view.midPriceIfPresent().ifPresent(mid ->
                gc.fillText("중간가 " + NUMBERS.format(mid) + "원", width - RIGHT - 130, height - 8));
    }

    // ------------------------------------------------------------------
    // 접근성
    // ------------------------------------------------------------------

    /** 지금 보이는 가격 구간을 설명에 적어, 확대·이동 결과를 눈으로 보지 않아도 알 수 있게 한다. */
    private void updateAccessibleDescription() {
        setAccessibleText("누적 호가 깊이 그래프. 같은 값을 호가 표에서 글자로 확인할 수 있습니다.");

        StringBuilder help = new StringBuilder(
                "위아래 방향키나 마우스 휠로 가격 구간을 옮기고, 더하기·빼기 또는 "
                        + "Ctrl과 휠로 확대합니다. Home 키로 처음 상태로 돌아갑니다.");
        visibleRange().ifPresent(range -> help.append(" 지금 보이는 구간은 ")
                .append(NUMBERS.format(range[0])).append("원부터 ")
                .append(NUMBERS.format(range[1])).append("원까지입니다."));
        setAccessibleHelp(help.toString());
    }

    private java.util.Optional<BigDecimal[]> visibleRange() {
        if (view == null || view.highestPrice() == null || view.lowestPrice() == null) {
            return java.util.Optional.empty();
        }
        BigDecimal low = view.lowestPrice();
        BigDecimal span = view.highestPrice().subtract(low);
        if (span.signum() <= 0) {
            return java.util.Optional.empty();
        }
        BigDecimal from = low.add(span.multiply(BigDecimal.valueOf(viewportLow())));
        BigDecimal to = low.add(span.multiply(BigDecimal.valueOf(viewportLow() + visibleSpan())));
        return java.util.Optional.of(new BigDecimal[]{from, to});
    }
}
