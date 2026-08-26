package org.ossproject.desktop.view.screen;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
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
import javafx.stage.Screen;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.desktop.viewmodel.StockDetailViewModel.ChartRange;
import org.ossproject.finance.model.market.PricePoint;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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

    private static final DateTimeFormatter TABLE_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String stockName;
    private final Function<BigDecimal, String> formatPrice;
    /** 기간을 바꾸면 그 기간의 값을 받아 온다. */
    private final Function<ChartRange, CompletionStage<List<PricePoint>>> loadHistory;
    /** 왼쪽 끝에 가까워지면 과거를 더 받아 온다. 더 없으면 지금 값을 그대로 준다. */
    private final Supplier<CompletionStage<List<PricePoint>>> loadOlder;
    /** 값이 바뀔 때마다 실시간 이어붙이기를 다시 건다. 스트림은 앱이 든다. */
    private final BiConsumer<CandlestickChartView, TableView<PricePoint>> attachLive;
    private final Consumer<String> onStatus;
    private final Runnable onLoadFailed;
    private final Runnable onSoundChart;
    /** 작은 차트와 크게 보기 창의 봉 주기 선택을 한 값으로 유지한다. */
    private ChartRange selectedRange = ChartRange.DAY;
    private final List<ToggleGroup> rangeGroups = new ArrayList<>();

    private record RangeSelector(HBox root, ToggleGroup group) {}

    public StockChartPanel(String stockName, Function<BigDecimal, String> formatPrice,
                           Function<ChartRange, CompletionStage<List<PricePoint>>> loadHistory,
                           Supplier<CompletionStage<List<PricePoint>>> loadOlder,
                           BiConsumer<CandlestickChartView, TableView<PricePoint>> attachLive,
                           Consumer<String> onStatus, Runnable onLoadFailed,
                           Runnable onSoundChart) {
        this.stockName = Objects.requireNonNull(stockName, "stockName");
        this.formatPrice = Objects.requireNonNull(formatPrice, "formatPrice");
        this.loadHistory = Objects.requireNonNull(loadHistory, "loadHistory");
        this.loadOlder = Objects.requireNonNull(loadOlder, "loadOlder");
        this.attachLive = Objects.requireNonNull(attachLive, "attachLive");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.onLoadFailed = Objects.requireNonNull(onLoadFailed, "onLoadFailed");
        this.onSoundChart = Objects.requireNonNull(onSoundChart, "onSoundChart");
    }

    public VBox create(List<PricePoint> initialPoints) {
        CandlestickChartView candles = new CandlestickChartView(initialPoints);
        candles.setInterval(selectedRange.interval());
        // 좁은 칸에서는 거래량을 끈다. 세로가 모자라면 거래량 띠가 창 밖으로 밀려,
        // 보이지도 않으면서 가격 차트의 자리만 가져간다. 값은 "접근 가능한 표" 의
        // 거래량 칸에 그대로 있고, 크게 보기 창에서는 다시 켠다.
        candles.setShowVolume(false);
        TableView<PricePoint> history = historyTable(initialPoints);
        attachOlderLoading(candles, history);

        TabPane representations = new TabPane(tab("그래프", candles), tab("접근 가능한 표", history));
        representations.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        representations.setMinHeight(0);
        // 이 칸의 주인공은 그래프다. 도구줄은 한 줄이면 되고 나머지는 전부 그래프가
        // 가져간다. 늘어나라는 뜻은 maxHeight 로 적는다 — prefHeight 에 MAX_VALUE 를
        // 넣으면 VBox 가 그 값으로 자리를 잡으려다 칸이 통째로 비어 버린다.
        representations.setPrefHeight(440);
        representations.setMaxHeight(Double.MAX_VALUE);
        representations.setAccessibleText(stockName + " 차트, 그래프와 표 탭");

        FlowPane toolbar = new FlowPane(8, 4,
                periodButtons(candles, history).root(), indicators(candles),
                latestButton(candles), enlargeButton(candles, history), soundChartButton());
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPrefWrapLength(1060);
        toolbar.getStyleClass().add("stock-chart-toolbar");

        VBox chart = new VBox(5, toolbar, representations);
        chart.setPadding(new Insets(4));
        chart.setMinHeight(0);
        VBox.setVgrow(representations, Priority.ALWAYS);
        attachLive.accept(candles, history);
        return chart;
    }

    private TableView<PricePoint> historyTable(List<PricePoint> points) {
        TableView<PricePoint> table = new TableView<>(FXCollections.observableArrayList(points));
        table.setAccessibleText(stockName + " 최근 가격 흐름 표");
        table.setAccessibleHelp("차트와 동일한 날짜와 시각별 시가, 고가, 저가, 종가와 거래량입니다.");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(column("날짜·시간", StockChartPanel::tableTime));
        table.getColumns().add(column("시가", point -> formatPrice.apply(point.open())));
        table.getColumns().add(column("고가", point -> formatPrice.apply(point.high())));
        table.getColumns().add(column("저가", point -> formatPrice.apply(point.low())));
        table.getColumns().add(column("종가", point -> formatPrice.apply(point.close())));
        table.getColumns().add(column("거래량", point -> Long.toString(point.volume())));
        org.ossproject.desktop.view.UiKit.alignByContent(table);
        table.setPrefHeight(350);
        return table;
    }

    /**
     * 차트를 큰 창으로 연다.
     *
     * <p>좁은 칸에서 끈 거래량을 여기서는 켠다. 화면을 거의 다 쓰므로 거래량 띠를 넣어도
     * 가격 차트가 눌리지 않는다. 확대·이동·가격축 끌기는 작은 칸과 똑같이 된다.
     */
    private Button enlargeButton(CandlestickChartView source, TableView<PricePoint> sourceHistory) {
        Button enlarge = new Button("크게 보기");
        enlarge.setAccessibleText("차트를 큰 창으로 열기");
        enlarge.setAccessibleHelp("거래량까지 함께 보이는 큰 창으로 엽니다. "
                + "끌어서 이동, 휠로 확대·축소, 오른쪽 가격축을 위아래로 끌어 세로 축척을 바꿉니다.");
        enlarge.setOnAction(event -> openLargeChart(source, sourceHistory));
        return enlarge;
    }

    private void openLargeChart(CandlestickChartView source, TableView<PricePoint> sourceHistory) {
        CandlestickChartView big = new CandlestickChartView(source.points());
        big.setInterval(selectedRange.interval());
        big.setShowVolume(true);
        big.setMinSize(0, 0);
        VBox.setVgrow(big, Priority.ALWAYS);

        TableView<PricePoint> bigHistory = historyTable(source.points());
        RangeSelector ranges = periodButtons(source, sourceHistory);
        CheckBox volume = new CheckBox("거래량");
        volume.setSelected(true);
        volume.setAccessibleText("거래량 막대 표시");
        volume.selectedProperty().addListener((observable, old, value) -> big.setShowVolume(value));

        FlowPane toolbar = new FlowPane(8, 5,
                ranges.root(), indicators(big), volume,
                zoomControls(big), latestButton(big));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPrefWrapLength(1500);
        toolbar.getStyleClass().addAll("stock-chart-toolbar", "large-chart-toolbar");

        TabPane representations = new TabPane(
                tab("캔들 차트", big), tab("시가·고가·저가·종가 표", bigHistory));
        representations.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        representations.setMinSize(0, 0);
        VBox.setVgrow(representations, Priority.ALWAYS);

        // 조작법은 화면 아래 한 줄을 차지하던 안내였다. 그래프에 그 높이를 돌려주되
        // 설명은 버리지 않는다. 차트를 읽는 순간 스크린 리더가 그대로 읽어 준다.
        // 눈으로 보는 도움말은 띄우지 않는다. 차트 한복판을 덮어 캔들을 가린다.
        big.setAccessibleHelp("끌어서 이동, 휠로 확대·축소, 오른쪽 가격축을 위아래로 끌면 세로 축척"
                + "(두 번 누르면 되돌림). 축척을 좁힌 뒤에는 세로로 끌거나 PageUp·PageDown 으로 "
                + "위아래로 옮겨 잘린 부분을 봅니다. 방향키와 Shift+위아래로도 됩니다. "
                + "화면 맞춤을 누르면 모두 되돌아갑니다. Esc 로 닫습니다.");

        VBox content = new VBox(6, toolbar, representations);
        content.setMinSize(0, 0);
        content.getStyleClass().add("large-chart-content");
        content.setAccessibleText(stockName + " 차트 크게 보기. 실시간으로 갱신됩니다. "
                + "같은 값이 접근 가능한 표에도 있습니다. Esc 를 누르면 닫힙니다.");

        Alert dialog = new Alert(Alert.AlertType.NONE);
        styleDialog(dialog);
        dialog.setTitle(stockName + " 차트");
        dialog.setHeaderText(null);
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.getStyleClass().add("large-chart-dialog");
        // 닫기 단추가 차지하던 아래 한 줄도 그래프에 넘긴다. 버튼 형식 자체는 남겨
        // 둬야 Esc 가 창을 닫고, 제목 표시줄의 닫기도 그대로 동작한다. 자리만 비운다.
        ButtonType close = new ButtonType("닫기", ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(close);
        Node closeButton = pane.lookupButton(close);
        if (closeButton != null) {
            closeButton.setVisible(false);
            closeButton.setManaged(false);
        }
        pane.setContent(content);
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        pane.setPrefSize(screen.getWidth() * 0.94, screen.getHeight() * 0.90);

        // 작은 차트가 받는 실시간 값을 그대로 받아 간다. 창이 따로 구독하면 실시간
        // 자리가 하나뿐이라 작은 차트 쪽 연결이 끊긴다.
        source.setPointsListener(points -> {
            big.setInterval(selectedRange.interval());
            big.setPoints(points);
            bigHistory.getItems().setAll(points);
        });
        attachLargeOlderLoading(source, sourceHistory, big);
        dialog.setOnShown(shown -> big.requestFocus());
        try {
            dialog.showAndWait();
        } finally {
            // 닫으면 반드시 뗀다. 남겨 두면 닫힌 창을 계속 그린다.
            source.setPointsListener(null);
            big.setOlderDataRequest(null);
            rangeGroups.remove(ranges.group());
        }
    }

    /** 크게 보기에서 과거로 이동해도 원본 차트의 캐시와 실시간 구독을 함께 유지한다. */
    private void attachLargeOlderLoading(CandlestickChartView source,
                                         TableView<PricePoint> sourceHistory,
                                         CandlestickChartView big) {
        AtomicBoolean loading = new AtomicBoolean();
        big.setOlderDataRequest(() -> {
            if (!loading.compareAndSet(false, true)) return;
            int before = sourceHistory.getItems().size();
            onStatus.accept(stockName + " 과거 구간을 불러오고 있습니다.");
            loadOlder.get().whenComplete((older, failure) -> {
                loading.set(false);
                if (failure != null) {
                    onStatus.accept(stockName + " 과거 구간을 불러오지 못했습니다.");
                    return;
                }
                if (older == null || older.size() <= before) {
                    onStatus.accept(stockName + " 더 받아올 과거 구간이 없습니다.");
                    return;
                }
                source.setPoints(older);
                sourceHistory.getItems().setAll(older);
                attachLive.accept(source, sourceHistory);
                onStatus.accept(stockName + " 과거 " + (older.size() - before)
                        + "개를 더 불러왔습니다.");
            });
        });
    }

    /**
     * 최신 구간으로 돌아가는 단추.
     *
     * <p>과거를 보고 있을 때만 나타난다. 단추가 보이는 것 자체가 "지금 최신이 아니다"를
     * 알려 준다. 화면을 훑기 어려운 사용자는 가격이 멈춘 이유를 알기 어렵기 때문이다.
     */
    private Button latestButton(CandlestickChartView candles) {
        Button latest = new Button("최신으로");
        latest.setAccessibleText("최신 구간으로 이동");
        latest.setAccessibleHelp("차트를 가장 최근 캔들이 보이는 위치로 되돌립니다. End 키로도 됩니다.");
        latest.setOnAction(event -> {
            candles.scrollToLatest();
            candles.requestFocus();
            onStatus.accept(stockName + " 차트를 최신 구간으로 옮겼습니다.");
        });

        Runnable sync = () -> {
            boolean atLatest = candles.isAtLatest();
            latest.setVisible(!atLatest);
            latest.setManaged(!atLatest);
        };
        candles.setViewportListener(sync);
        sync.run();
        return latest;
    }

    /**
     * 왼쪽 끝에 가까워지면 과거를 이어 받는다.
     *
     * <p>받아 온 개수가 늘었을 때만 화면을 갱신한다. 같은 값으로 다시 그리면 뷰포트 알림이
     * 또 울려 요청이 끝없이 되풀이된다.
     *
     * <p>조회 중에는 새 요청을 만들지 않는다. 끄는 동안 경계에 여러 번 닿기 때문이다.
     * 이어붙인 사실은 소리로도 알린다. 화면을 볼 수 없는 사용자에게는 구간이 늘어난 것이
     * 유일한 단서다.
     */
    private void attachOlderLoading(CandlestickChartView candles, TableView<PricePoint> history) {
        AtomicBoolean loading = new AtomicBoolean();
        candles.setOlderDataRequest(() -> {
            if (!loading.compareAndSet(false, true)) {
                return;
            }
            int before = history.getItems().size();
            onStatus.accept(stockName + " 과거 구간을 불러오고 있습니다.");
            loadOlder.get().whenComplete((older, failure) -> {
                loading.set(false);
                if (failure != null) {
                    onStatus.accept(stockName + " 과거 구간을 불러오지 못했습니다.");
                    return;
                }
                if (older == null || older.size() <= before) {
                    onStatus.accept(stockName + " 더 받아올 과거 구간이 없습니다.");
                    return;
                }
                candles.setPoints(older);
                history.getItems().setAll(older);
                attachLive.accept(candles, history);
                onStatus.accept(stockName + " 과거 " + (older.size() - before)
                        + "개를 더 불러왔습니다. 전체 " + older.size() + "개입니다.");
            });
        });
    }

    private RangeSelector periodButtons(CandlestickChartView candles, TableView<PricePoint> history) {
        HBox periods = new HBox(5);
        periods.setAlignment(Pos.CENTER_LEFT);
        periods.getStyleClass().add("stock-periods");
        ToggleGroup group = new ToggleGroup();
        rangeGroups.add(group);
        Map<ToggleButton, ChartRange> buttons = new LinkedHashMap<>();
        for (ChartRange range : ChartRange.values()) {
            ToggleButton button = new ToggleButton(range.label());
            button.setToggleGroup(group);
            button.setUserData(range);
            button.getStyleClass().add("stock-chart-toggle");
            button.setAccessibleText(range.label() + " 차트로 바꾸기");
            if (range == selectedRange) {
                button.setSelected(true);
            }
            buttons.put(button, range);
            periods.getChildren().add(button);
        }
        buttons.forEach((button, range) -> button.setOnAction(event -> {
            // 조회가 끝나기 전에는 실제 차트가 아직 이전 주기다. 선택 표시도 이전 값으로
            // 되돌려 데이터와 버튼이 서로 다른 말을 하지 않게 한다.
            selectRange(group, selectedRange);
            switchRange(button, range, candles, history);
        }));
        return new RangeSelector(periods, group);
    }

    private void switchRange(ToggleButton button, ChartRange range,
                             CandlestickChartView candles, TableView<PricePoint> history) {
        // 조회가 오가는 사이 다른 기간을 누르면 나중에 온 응답이 먼저 온 것을 덮어쓴다.
        setRangeControlsDisabled(true);
        onStatus.accept(stockName + " " + range.label() + " 차트를 조회하고 있습니다.");
        loadHistory.apply(range).whenComplete((updated, failure) -> {
            setRangeControlsDisabled(false);
            if (failure != null || updated == null || updated.isEmpty()) {
                // 이전 기간의 값을 그대로 둔다. 비워 버리면 고른 기간에 값이 없는 것으로 읽힌다.
                onStatus.accept(stockName + " 차트를 조회하지 못했습니다.");
                onLoadFailed.run();
                syncRangeControls();
                return;
            }
            selectedRange = range;
            candles.setInterval(range.interval());
            candles.setPoints(updated);
            history.getItems().setAll(updated);
            syncRangeControls();
            attachLive.accept(candles, history);
            onStatus.accept(stockName + " " + range.label() + " 차트로 변경했습니다.");
        });
    }

    private void setRangeControlsDisabled(boolean disabled) {
        for (ToggleGroup group : List.copyOf(rangeGroups)) {
            group.getToggles().forEach(toggle -> ((ToggleButton) toggle).setDisable(disabled));
        }
    }

    private void syncRangeControls() {
        for (ToggleGroup group : List.copyOf(rangeGroups)) selectRange(group, selectedRange);
    }

    private static void selectRange(ToggleGroup group, ChartRange range) {
        group.getToggles().stream()
                .filter(toggle -> toggle.getUserData() == range)
                .findFirst().ifPresent(group::selectToggle);
    }

    /** 마우스 휠을 쓰기 어려운 사용자를 위한 명시적 차트 조작 단추. */
    private HBox zoomControls(CandlestickChartView candles) {
        Button zoomIn = new Button("확대 +");
        zoomIn.setAccessibleText("차트 가로축 확대");
        zoomIn.setOnAction(event -> { candles.zoomIn(); candles.requestFocus(); });
        Button zoomOut = new Button("축소 −");
        zoomOut.setAccessibleText("차트 가로축 축소");
        zoomOut.setOnAction(event -> { candles.zoomOut(); candles.requestFocus(); });
        Button fit = new Button("화면 맞춤");
        fit.setAccessibleText("차트 확대와 가격축을 초기화하고 최신으로 이동");
        fit.setOnAction(event -> { candles.resetView(); candles.requestFocus(); });
        HBox controls = new HBox(5, zoomIn, zoomOut, fit);
        controls.getStyleClass().add("chart-zoom-controls");
        return controls;
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

    private static String tableTime(PricePoint point) {
        return point.timestamp().toLocalTime().equals(LocalTime.MIDNIGHT)
                ? point.date().toString()
                : point.timestamp().format(TABLE_DATE_TIME);
    }

    /** 지표 이름을 한 곳에서 센다. 검사가 개수를 확인할 때 쓴다. */
    public static List<String> indicatorNames() {
        return new ArrayList<>(List.of("이동평균", "Bollinger Band", "RSI", "MACD"));
    }
}
