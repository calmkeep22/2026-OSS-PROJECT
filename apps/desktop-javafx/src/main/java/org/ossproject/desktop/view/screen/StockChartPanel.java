package org.ossproject.desktop.view.screen;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.desktop.viewmodel.StockDetailViewModel.ChartRange;
import org.ossproject.finance.model.market.PricePoint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 차트 칸. 그래프와 표를 같은 값으로 함께 보여 준다.
 *
 * <p>표가 곁들이가 아니다. 그래프는 스크린리더가 읽을 수 없어서, 표가 없으면 화면을 볼
 * 수 없는 사용자에게는 이 칸이 통째로 비어 있는 것과 같다. 그래서 둘은 늘 같은 값을
 * 들고 있어야 하고, 기간을 바꾸거나 실시간 값이 와도 함께 바뀐다.
 *
 * <p>기간 단추는 누르는 동안 잠근다. 조회가 오가는 사이 다른 기간을 누르면 나중에 온
 * 응답이 먼저 온 것을 덮어써, 고른 기간과 보이는 값이 어긋난다.
 */
public final class StockChartPanel {

    private final String stockName;
    private final Function<BigDecimal, String> formatPrice;
    /** 기간을 바꾸면 그 기간의 값을 받아 온다. */
    private final Function<ChartRange, CompletionStage<List<PricePoint>>> loadHistory;
    /** 값이 바뀔 때마다 실시간 이어붙이기를 다시 건다. 스트림은 앱이 든다. */
    private final BiConsumer<CandlestickChartView, TableView<PricePoint>> attachLive;
    private final Consumer<String> onStatus;
    private final Runnable onLoadFailed;
    private final Runnable onSoundChart;

    public StockChartPanel(String stockName, Function<BigDecimal, String> formatPrice,
                           Function<ChartRange, CompletionStage<List<PricePoint>>> loadHistory,
                           BiConsumer<CandlestickChartView, TableView<PricePoint>> attachLive,
                           Consumer<String> onStatus, Runnable onLoadFailed,
                           Runnable onSoundChart) {
        this.stockName = Objects.requireNonNull(stockName, "stockName");
        this.formatPrice = Objects.requireNonNull(formatPrice, "formatPrice");
        this.loadHistory = Objects.requireNonNull(loadHistory, "loadHistory");
        this.attachLive = Objects.requireNonNull(attachLive, "attachLive");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.onLoadFailed = Objects.requireNonNull(onLoadFailed, "onLoadFailed");
        this.onSoundChart = Objects.requireNonNull(onSoundChart, "onSoundChart");
    }

    public VBox create(List<PricePoint> initialPoints) {
        CandlestickChartView candles = new CandlestickChartView(initialPoints);
        TableView<PricePoint> history = historyTable(initialPoints);

        TabPane representations = new TabPane(tab("그래프", candles), tab("접근 가능한 표", history));
        representations.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        representations.setMinHeight(0);
        representations.setPrefHeight(440);
        representations.setAccessibleText(stockName + " 차트, 그래프와 표 탭");

        FlowPane toolbar = new FlowPane(10, 6,
                periodButtons(candles, history), indicators(candles), soundChartButton());
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPrefWrapLength(1060);
        toolbar.getStyleClass().add("stock-chart-toolbar");

        VBox chart = new VBox(7, toolbar, representations);
        chart.setPadding(new Insets(6));
        chart.setMinHeight(0);
        VBox.setVgrow(representations, Priority.ALWAYS);
        attachLive.accept(candles, history);
        return chart;
    }

    private TableView<PricePoint> historyTable(List<PricePoint> points) {
        TableView<PricePoint> table = new TableView<>(FXCollections.observableArrayList(points));
        table.setAccessibleText(stockName + " 최근 가격 흐름 표");
        table.setAccessibleHelp("차트와 동일한 날짜별 시가, 고가, 저가, 종가와 거래량입니다.");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(column("날짜", point -> point.date().toString()));
        table.getColumns().add(column("시가", point -> formatPrice.apply(point.open())));
        table.getColumns().add(column("고가", point -> formatPrice.apply(point.high())));
        table.getColumns().add(column("저가", point -> formatPrice.apply(point.low())));
        table.getColumns().add(column("종가", point -> formatPrice.apply(point.close())));
        table.getColumns().add(column("거래량", point -> Long.toString(point.volume())));
        table.setPrefHeight(350);
        return table;
    }

    private HBox periodButtons(CandlestickChartView candles, TableView<PricePoint> history) {
        HBox periods = new HBox(5);
        periods.setAlignment(Pos.CENTER_LEFT);
        periods.getStyleClass().add("stock-periods");
        ToggleGroup group = new ToggleGroup();
        Map<ToggleButton, ChartRange> buttons = new LinkedHashMap<>();
        for (ChartRange range : ChartRange.values()) {
            ToggleButton button = new ToggleButton(range.label());
            button.setToggleGroup(group);
            button.getStyleClass().add("stock-chart-toggle");
            button.setAccessibleText(range.label() + " 차트로 바꾸기");
            if (range == ChartRange.DAY) {
                button.setSelected(true);
            }
            buttons.put(button, range);
            periods.getChildren().add(button);
        }
        buttons.forEach((button, range) -> button.setOnAction(event ->
                switchRange(button, range, candles, history)));
        return periods;
    }

    private void switchRange(ToggleButton button, ChartRange range,
                             CandlestickChartView candles, TableView<PricePoint> history) {
        // 조회가 오가는 사이 다른 기간을 누르면 나중에 온 응답이 먼저 온 것을 덮어쓴다.
        button.setDisable(true);
        onStatus.accept(stockName + " " + range.label() + " 차트를 조회하고 있습니다.");
        loadHistory.apply(range).whenComplete((updated, failure) -> {
            button.setDisable(false);
            if (failure != null || updated == null || updated.isEmpty()) {
                // 이전 기간의 값을 그대로 둔다. 비워 버리면 고른 기간에 값이 없는 것으로 읽힌다.
                onStatus.accept(stockName + " 차트를 조회하지 못했습니다.");
                onLoadFailed.run();
                return;
            }
            candles.setPoints(updated);
            history.getItems().setAll(updated);
            attachLive.accept(candles, history);
            onStatus.accept(stockName + " " + range.label() + " 차트로 변경했습니다.");
        });
    }

    private HBox indicators(CandlestickChartView candles) {
        CheckBox movingAverage = new CheckBox("이동평균");
        movingAverage.setSelected(true);
        CheckBox bollinger = new CheckBox("Bollinger Band");
        CheckBox rsi = new CheckBox("RSI");
        CheckBox macd = new CheckBox("MACD");
        movingAverage.selectedProperty().addListener(
                (observable, old, value) -> candles.setShowMovingAverages(value));
        bollinger.selectedProperty().addListener(
                (observable, old, value) -> candles.setShowBollinger(value));
        rsi.selectedProperty().addListener((observable, old, value) -> candles.setShowRsi(value));
        macd.selectedProperty().addListener((observable, old, value) -> candles.setShowMacd(value));

        HBox row = new HBox(8, movingAverage, bollinger, rsi, macd);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("stock-indicators");
        return row;
    }

    /** 그래프를 못 읽는 사용자에게 같은 값을 소리로 주는 길. 차트 옆에 둔다. */
    private javafx.scene.control.Button soundChartButton() {
        javafx.scene.control.Button button = new javafx.scene.control.Button("이 차트를 소리로 탐색");
        button.getStyleClass().add("stock-compact-action");
        button.setOnAction(event -> onSoundChart.run());
        return button;
    }

    private static TableColumn<PricePoint, String> column(String title,
                                                          Function<PricePoint, String> value) {
        TableColumn<PricePoint, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return column;
    }

    /** 지표 이름을 한 곳에서 센다. 검사가 개수를 확인할 때 쓴다. */
    public static List<String> indicatorNames() {
        return new ArrayList<>(List.of("이동평균", "Bollinger Band", "RSI", "MACD"));
    }
}
