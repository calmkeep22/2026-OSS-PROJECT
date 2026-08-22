package org.ossproject.desktop.chart;

import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.ossproject.finance.model.market.PricePoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

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
    private double crossX = -1;
    private double crossY = -1;
    private boolean showMa = true;
    private boolean showBollinger;
    private boolean showRsi;
    private boolean showMacd;

    public CandlestickChartView(List<PricePoint> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("차트 데이터는 한 건 이상이어야 합니다.");
        }
        this.points = List.copyOf(points);
        this.visibleCount = Math.min(30, points.size());
        getChildren().add(canvas);
        setMinHeight(320);
        setPrefHeight(390);
        setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        setAccessibleText(buildAccessibleSummary());
        setAccessibleHelp("마우스 휠로 기간을 확대하거나 축소하고, 마우스를 움직이면 해당 날짜와 가격을 확인합니다. 표 탭에서 같은 데이터를 읽을 수 있습니다.");

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
        canvas.setOnScroll(event -> {
            int change = event.getDeltaY() > 0 ? -4 : 4;
            visibleCount = Math.max(10, Math.min(points.size(), visibleCount + change));
            draw();
            event.consume();
        });
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
        points = List.copyOf(updatedPoints);
        visibleCount = Math.min(30, points.size());
        crossX = -1; crossY = -1;
        setAccessibleText(buildAccessibleSummary());
        draw();
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

        int start = Math.max(0, points.size() - visibleCount);
        List<PricePoint> visible = points.subList(start, points.size());
        double plotRight = width - RIGHT;
        double usableHeight = Math.max(120, height - TOP - BOTTOM);
        double mainBottom = TOP + usableHeight * 0.72;
        double indicatorTop = mainBottom + 14;
        double indicatorBottom = height - BOTTOM;

        double min = visible.stream().map(PricePoint::low).mapToDouble(BigDecimal::doubleValue).min().orElse(0);
        double max = visible.stream().map(PricePoint::high).mapToDouble(BigDecimal::doubleValue).max().orElse(1);
        double padding = Math.max(1, (max - min) * 0.08);
        min -= padding;
        max += padding;

        drawGrid(g, plotRight, mainBottom, indicatorTop, indicatorBottom, min, max);

        double slot = (plotRight - LEFT) / visible.size();
        double candleWidth = Math.max(3, Math.min(14, slot * 0.62));
        long maxVolume = visible.stream().mapToLong(PricePoint::volume).max().orElse(1);

        for (int index = 0; index < visible.size(); index++) {
            PricePoint point = visible.get(index);
            double x = LEFT + slot * index + slot / 2;
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

            double volumeHeight = (indicatorBottom - indicatorTop) * point.volume() / Math.max(1d, maxVolume);
            g.setGlobalAlpha(0.52);
            g.fillRect(x - candleWidth / 2, indicatorBottom - volumeHeight, candleWidth, volumeHeight);
            g.setGlobalAlpha(1);
        }

        if (showMa) {
            drawAverage(g, visible, 5, Color.web("#c084fc"), min, max, mainBottom, slot);
            drawAverage(g, visible, 20, Color.web("#fbbf24"), min, max, mainBottom, slot);
        }
        if (showBollinger) drawBollinger(g, visible, min, max, mainBottom, slot);
        if (showRsi) drawRsi(g, visible, indicatorTop, indicatorBottom, slot);
        if (showMacd) drawMacd(g, visible, indicatorTop, indicatorBottom, slot);

        double previousClose = points.get(Math.max(0, start - 1)).close().doubleValue();
        drawPriceLine(g, previousClose, min, max, mainBottom, width, "전일", Color.web("#64748b"));
        drawCurrentPrice(g, visible.get(visible.size() - 1), min, max, mainBottom, plotRight);
        drawTimeAxis(g, visible, slot, height);
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
        g.fillText("거래량", LEFT + 4, indicatorTop + 4);
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
            double x = LEFT + slot * i + slot / 2;
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
            double x = LEFT + slot * i + slot / 2;
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
            double x = LEFT + slot * i + slot / 2;
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
            double x = LEFT + slot * i + slot * 0.2;
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

    private void drawTimeAxis(GraphicsContext g, List<PricePoint> visible, double slot, double height) {
        int step = Math.max(1, (int) Math.ceil(visible.size() / 6d));
        g.setFill(Color.web("#94a3b8"));
        g.setTextAlign(TextAlignment.CENTER);
        for (int index = 0; index < visible.size(); index += step) {
            PricePoint point = visible.get(index);
            double x = LEFT + slot * index + slot / 2;
            g.fillText(String.format("%02d/%02d", point.date().getMonthValue(), point.date().getDayOfMonth()),
                    x, height - 10);
        }
    }

    private void drawOhlcHeader(GraphicsContext g, PricePoint point) {
        boolean up = point.close().compareTo(point.open()) >= 0;
        g.setFill(Color.web(up ? "#ff6b6d" : "#60a5fa"));
        g.setTextAlign(TextAlignment.LEFT);
        String text = point.date() + "  O " + formatPrice(point.open())
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

        int index = Math.max(0, Math.min(visible.size() - 1, (int) ((crossX - LEFT) / slot)));
        PricePoint point = visible.get(index);
        double price = max - (crossY - TOP) / (mainBottom - TOP) * (max - min);
        String label = point.date() + "  O " + formatPrice(point.open())
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

    private String buildAccessibleSummary() {
        PricePoint first = points.get(0);
        PricePoint last = points.get(points.size() - 1);
        BigDecimal change = last.close().subtract(first.close());
        String direction = change.signum() > 0 ? "상승" : change.signum() < 0 ? "하락" : "보합";
        return "캔들 차트. " + first.date() + "부터 " + last.date() + "까지, 시작 종가 "
                + format(first.close()) + ", 마지막 종가 " + format(last.close()) + ", " + direction + " "
                + format(change.abs()) + ".";
    }

    private String format(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatPrice(BigDecimal value) {
        return String.format("%,.0f", value.doubleValue());
    }
}
