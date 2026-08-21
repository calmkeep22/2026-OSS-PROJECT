package org.ossproject.desktop.orderbook;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.ossproject.finance.model.DepthChartView;

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
 * <p>이 그래프는 보조 표현이다. 같은 값을 호가 표가 글자로 보여 주고, 스크린리더는 그쪽을
 * 읽는다. 그래서 여기에는 접근 가능한 이름만 붙이고 키보드 초점은 주지 않는다.
 */
public final class DepthChartCanvas extends Region {

    private static final double LEFT = 70;
    private static final double RIGHT = 16;
    private static final double TOP = 16;
    private static final double BOTTOM = 24;
    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.KOREA);

    private static final Color ASK = Color.web("#c0392b");
    private static final Color BID = Color.web("#1f6fb2");
    private static final Color WALL = Color.web("#8e44ad");
    private static final Color AXIS = Color.web("#9aa4ad");
    private static final Color TEXT = Color.web("#3c4650");

    private final Canvas canvas = new Canvas();
    private DepthChartView view;

    public DepthChartCanvas() {
        getChildren().add(canvas);
        setMinHeight(240);
        setPrefHeight(320);
        setAccessibleText("누적 호가 깊이 그래프. 같은 값을 호가 표에서 글자로 확인할 수 있습니다.");
    }

    public void update(DepthChartView updated) {
        this.view = updated;
        requestLayout();
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

        drawSide(gc, view.askPoints(), ASK, plotWidth, plotHeight);
        drawSide(gc, view.bidPoints(), BID, plotWidth, plotHeight);
        drawScale(gc, width, height);
    }

    /** 정규화된 비율에 폭과 높이를 곱해 막대로 그린다. */
    private void drawSide(GraphicsContext gc, List<DepthChartView.Plot> points,
                          Color color, double plotWidth, double plotHeight) {
        double barHeight = Math.max(2, plotHeight / Math.max(1, view.askPoints().size()
                + view.bidPoints().size()) - 1);
        for (DepthChartView.Plot plot : points) {
            double y = TOP + (1.0 - plot.priceRatio()) * plotHeight;
            double length = plot.depthRatio() * plotWidth;
            gc.setFill(plot.wall() ? WALL : color);
            gc.fillRect(LEFT + 1, y - barHeight / 2, Math.max(1, length), barHeight);

            gc.setFill(TEXT);
            gc.fillText(NUMBERS.format(plot.price()), 6, y + 4);
        }
    }

    private void drawScale(GraphicsContext gc, double width, double height) {
        gc.setFill(TEXT);
        String scale = "가로축 기준 누적 " + NUMBERS.format(view.depthScale()) + "주";
        gc.fillText(scale, LEFT, height - 8);
        view.midPriceIfPresent().ifPresent(mid ->
                gc.fillText("중간가 " + NUMBERS.format(mid) + "원", width - RIGHT - 130, height - 8));
    }
}
