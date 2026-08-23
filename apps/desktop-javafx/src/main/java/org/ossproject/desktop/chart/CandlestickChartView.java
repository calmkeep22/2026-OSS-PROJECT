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
    private double dragAnchorX = Double.NaN;
    private double dragAnchorStart;
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

    public CandlestickChartView(List<PricePoint> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("차트 데이터는 한 건 이상이어야 합니다.");
        }
        this.points = List.copyOf(points);
        this.visibleCount = Math.min(30, points.size());
        this.startIndex = Math.max(0, points.size() - visibleCount);
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
            dragAnchorX = event.getX();
            dragAnchorStart = startIndex;
            event.consume();
        });
        canvas.setOnMouseDragged(event -> {
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
            event.consume();
        });
        canvas.setOnMouseReleased(event -> dragAnchorX = Double.NaN);

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
    }

    /** 최신 캔들이 오른쪽 끝에 오도록 되돌린다. */
    public void scrollToLatest() {
        setStartIndex(points.size() - visibleCount);
    }

    /** 지금 최신 구간을 보고 있는지 여부. 화면이 "최신으로" 단추를 감출지 판단한다. */
    public boolean isAtLatest() {
        return startIndex >= points.size() - visibleCount - 1e-6;
    }

    /** 시작 위치를 자료 범위 안에 가둔다. */
    private void setStartIndex(double index) {
        double maxStart = Math.max(0, points.size() - visibleCount);
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

    /** 보이는 구간이 바뀔 때 화면에 알린다. */
    private void notifyViewportChanged() {
        setAccessibleText(buildAccessibleSummary());
        if (viewportListener != null) {
            viewportListener.run();
        }
    }

    /** 보이는 구간이 바뀔 때 실행할 동작. "최신으로" 단추를 켜고 끄는 데 쓴다. */
    public void setViewportListener(Runnable listener) {
        this.viewportListener = listener;
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
        points = List.copyOf(updatedPoints);
        visibleCount = Math.min(30, points.size());
        // 최신을 보고 있었다면 새 캔들을 따라가고, 과거를 살펴보던 중이었다면
        // 보던 자리를 지킨다. 읽던 구간이 갑자기 끌려가면 다시 찾아야 한다.
        startIndex = wasAtLatest
                ? Math.max(0, points.size() - visibleCount)
                : Math.max(0, Math.min(startIndex, Math.max(0, points.size() - visibleCount)));
        crossX = -1; crossY = -1;
        setAccessibleText(buildAccessibleSummary());
        draw();
        notifyViewportChanged();
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
        int start = (int) Math.floor(Math.max(0, Math.min(startIndex, points.size() - visibleCount)));
        double fraction = Math.max(0, Math.min(startIndex, points.size() - visibleCount)) - start;
        int from = Math.max(0, start - 1);
        int to = Math.min(points.size(), start + visibleCount + 2);
        List<PricePoint> visible = points.subList(from, to);
        // 잘라낸 시작점이 앞으로 밀린 만큼 되돌려 준다.
        double shiftCandles = fraction + (start - from);
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

        // 여유분까지 나누면 캔들이 좁아진다. 폭은 실제로 보여 줄 개수로 정한다.
        double slot = (plotRight - LEFT) / visibleCount;
        shiftPx = shiftCandles * slot;
        double candleWidth = Math.max(3, Math.min(14, slot * 0.62));
        long maxVolume = visible.stream().mapToLong(PricePoint::volume).max().orElse(1);

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

        // 그릴 때 민 만큼 되돌려야 커서 아래 캔들을 정확히 집는다.
        int index = Math.max(0, Math.min(visible.size() - 1, (int) ((crossX - LEFT + shiftPx) / slot)));
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

    /**
     * 지금 보이는 구간을 설명한다.
     *
     * <p>전체 자료가 아니라 보이는 구간을 기준으로 삼는다. 그렇지 않으면 사용자가
     * 좌우로 옮겨도 읽히는 내용이 그대로여서, 옮겼는지 알 수 없다.
     */
    private String buildAccessibleSummary() {
        int start = (int) Math.round(Math.max(0, Math.min(startIndex, points.size() - visibleCount)));
        start = Math.max(0, Math.min(start, Math.max(0, points.size() - visibleCount)));
        List<PricePoint> visible = points.subList(start, Math.min(points.size(), start + visibleCount));
        PricePoint first = visible.get(0);
        PricePoint last = visible.get(visible.size() - 1);
        BigDecimal change = last.close().subtract(first.close());
        String direction = change.signum() > 0 ? "상승" : change.signum() < 0 ? "하락" : "보합";
        String position = isAtLatest() ? "최신 구간" : "과거 구간";
        return "캔들 차트. " + position + " " + visible.size() + "개, " + first.date() + "부터 "
                + last.date() + "까지, 시작 종가 " + format(first.close()) + ", 마지막 종가 "
                + format(last.close()) + ", " + direction + " " + format(change.abs()) + ".";
    }

    private String format(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatPrice(BigDecimal value) {
        return String.format("%,.0f", value.doubleValue());
    }
}
