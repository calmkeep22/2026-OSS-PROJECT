package org.ossproject.desktop.chart;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.ossproject.sonification.GraphPlaybackState;
import org.ossproject.sonification.model.GraphScaleMode;

import java.util.List;
import java.util.Objects;

/** Builds the JavaFX controls for the accessible chart and delegates all behavior to its controller. */
public final class AccessibleChartView {
    private final AccessibleChartController controller;
    private final ScrollPane root;
    private final ListView<String> pointList;
    private final ComboBox<Double> speedSelector;

    public AccessibleChartView(AccessibleChartController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");

        Label title = heading("청각 차트");
        Label notice = new Label("그래프의 시간축은 실제 날짜 간격에 비례한 재생 시간, 가격축은 음높이로 표현합니다. "
                + "전체 흐름을 소리로 파악한 뒤 필요한 지점의 정확한 가격을 음성으로 확인하세요. "
                + "투자 추천이나 매수·매도 신호가 아닙니다.");
        notice.getStyleClass().add("safety-note");
        notice.setWrapText(true);

        var stockDetail = controller.stock();
        Label stock = new Label(stockDetail.name() + " (" + stockDetail.symbol() + ") · 최근 1개월 종가");
        stock.getStyleClass().add("section-title");
        Label summary = new Label(controller.summaryText());
        summary.setWrapText(true);
        summary.getStyleClass().add("chart-summary");
        summary.setAccessibleText("차트 전체 요약. " + summary.getText());
        Button listenSummary = new Button("전체 요약 듣기 (S)");
        listenSummary.setOnAction(event -> controller.announceSummary());

        Label playbackState = new Label();
        playbackState.textProperty().bind(controller.playbackStatusProperty());
        playbackState.getStyleClass().add("radio-status");
        playbackState.setWrapText(true);

        Button playChart = new Button("전체 그래프 재생 (Space)");
        playChart.setAccessibleHelp("원본 지점은 유지하고 핵심 굴곡은 최대 48개로 줄여 약 12초 동안 연속음으로 재생합니다.");
        playChart.setOnAction(event -> controller.play());
        Button pauseChart = new Button("일시정지");
        pauseChart.setOnAction(event -> controller.pause());
        Button replayChart = new Button("처음부터 다시 듣기 (R)");
        replayChart.setOnAction(event -> controller.replay());
        Button stopChart = new Button("재생 중지");
        stopChart.setOnAction(event -> controller.stop());
        HBox playbackControls = new HBox(10, playChart, pauseChart, replayChart, stopChart);
        playbackControls.setAlignment(Pos.CENTER_LEFT);

        ComboBox<GraphScaleMode> scale = createScaleSelector();
        Label mapping = new Label();
        mapping.textProperty().bind(controller.scaleDescriptionProperty());
        mapping.setWrapText(true);
        ComboBox<Double> percentRange = createPercentRangeSelector(scale);
        speedSelector = createSpeedSelector();
        Slider volume = new Slider(0, 100, controller.volume() * 100);
        volume.setShowTickLabels(true);
        volume.setMajorTickUnit(25);
        volume.setAccessibleText("청각 차트 음량");
        volume.valueProperty().addListener((obs, old, selected) ->
                controller.setVolume(selected.doubleValue() / 100.0));

        GridPane options = new GridPane();
        options.setHgap(16);
        options.setVgap(10);
        addField(options, 0, "음역 기준", scale);
        addField(options, 1, "고정 등락 범위", percentRange);
        addField(options, 2, "재생 속도", speedSelector);
        addField(options, 3, "음량", volume);
        options.add(mapping, 0, 4, 2, 1);

        pointList = new ListView<>(controller.pointLabels());
        pointList.setAccessibleText("청각 차트 가격 지점 목록");
        pointList.setAccessibleHelp("좌우 방향키로 한 지점, 컨트롤과 좌우 방향키로 세 지점을 이동합니다. "
                + "Enter 키는 정확한 시간과 가격을 읽고 Space 키는 전체 재생을 시작하거나 멈춥니다.");
        pointList.addEventFilter(KeyEvent.KEY_PRESSED, this::handleNavigationKey);
        pointList.getSelectionModel().selectFirst();
        controller.selectedIndexProperty().addListener((obs, old, selected) -> {
            int index = selected.intValue();
            if (index >= 0) {
                pointList.getSelectionModel().select(index);
                pointList.scrollTo(index);
            }
        });

        Button listenSelected = new Button("선택 지점의 정확한 값 듣기 (Enter)");
        listenSelected.setOnAction(event -> announceSelectedPoint());
        Label keyboardHelp = new Label("가격 지점 목록 키보드: ←/→ 한 지점 · Ctrl+←/→ 세 지점 · Home/End 처음/끝 · "
                + "Enter 정확한 값 · Space 재생/일시정지 · +/- 속도 · S 요약 · R 다시 듣기");
        keyboardHelp.getStyleClass().add("keyboard-help");
        keyboardHelp.setWrapText(true);

        Label liveDescription = new Label("실시간 모니터링은 Fake 가격이 들어오는 순서대로 같은 음역 규칙을 적용합니다.");
        liveDescription.setWrapText(true);
        Label liveState = new Label();
        liveState.textProperty().bind(controller.liveStatusProperty());
        Button liveStart = new Button("실시간 모니터링 시작 (Fake)");
        liveStart.setOnAction(event -> controller.startLive());
        Button liveStop = new Button("실시간 모니터링 중지");
        liveStop.setOnAction(event -> controller.stopLive());
        HBox liveControls = new HBox(10, liveStart, liveStop);

        VBox body = new VBox(18, title, notice, stock, sectionHeading("1. 전체 요약"), summary, listenSummary,
                sectionHeading("2. 전체 그래프 듣기"), playbackState, playbackControls,
                sectionHeading("재생 설정"), options,
                sectionHeading("3. 가격 지점 정밀 탐색"), keyboardHelp, listenSelected, pointList,
                sectionHeading("실시간 모니터링"), liveDescription, liveState, liveControls);
        body.getStyleClass().add("screen-content");
        body.setPadding(new Insets(32));
        VBox.setVgrow(pointList, Priority.ALWAYS);
        root = new ScrollPane(body);
        root.setFitToWidth(true);
        root.setFitToHeight(true);
        root.setAccessibleText("청각 차트 화면");
    }

    public ScrollPane root() { return root; }

    private ComboBox<GraphScaleMode> createScaleSelector() {
        ComboBox<GraphScaleMode> selector = new ComboBox<>(
                FXCollections.observableArrayList(GraphScaleMode.values()));
        selector.setValue(controller.scaleMode());
        selector.setConverter(new StringConverter<>() {
            @Override public String toString(GraphScaleMode value) {
                if (value == null) return "";
                return value == GraphScaleMode.AUTOMATIC ? "자동 범위" : "첫 종가 기준 고정 범위";
            }
            @Override public GraphScaleMode fromString(String value) { return GraphScaleMode.AUTOMATIC; }
        });
        selector.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) controller.setScaleMode(selected);
        });
        return selector;
    }

    private ComboBox<Double> createPercentRangeSelector(ComboBox<GraphScaleMode> scale) {
        ComboBox<Double> selector = new ComboBox<>(FXCollections.observableArrayList(1.0, 3.0, 5.0, 10.0));
        selector.setValue(controller.percentRange());
        selector.setDisable(controller.scaleMode() == GraphScaleMode.AUTOMATIC);
        selector.setConverter(new StringConverter<>() {
            @Override public String toString(Double value) { return value == null ? "" : "±" + value.intValue() + "%"; }
            @Override public Double fromString(String value) {
                return Double.parseDouble(value.replace("±", "").replace("%", ""));
            }
        });
        scale.valueProperty().addListener((obs, old, selected) ->
                selector.setDisable(selected == GraphScaleMode.AUTOMATIC));
        selector.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) controller.setPercentRange(selected);
        });
        return selector;
    }

    private ComboBox<Double> createSpeedSelector() {
        ComboBox<Double> selector = new ComboBox<>(FXCollections.observableArrayList(0.5, 1.0, 2.0, 4.0));
        selector.setValue(controller.speed());
        selector.setConverter(new StringConverter<>() {
            @Override public String toString(Double value) { return value == null ? "" : value + "배"; }
            @Override public Double fromString(String value) { return Double.parseDouble(value.replace("배", "")); }
        });
        selector.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) controller.setSpeed(selected);
        });
        return selector;
    }

    private void handleNavigationKey(KeyEvent event) {
        switch (event.getCode()) {
            case LEFT -> moveSelection(event.isControlDown() ? -3 : -1);
            case RIGHT -> moveSelection(event.isControlDown() ? 3 : 1);
            case HOME -> selectPoint(0);
            case END -> selectPoint(controller.samples().size() - 1);
            case ENTER -> announceSelectedPoint();
            case SPACE -> {
                if (controller.playbackState() == GraphPlaybackState.PLAYING) controller.pause();
                else controller.play();
            }
            case S -> controller.announceSummary();
            case R -> controller.replay();
            case ADD, PLUS, EQUALS -> adjustSpeed(1);
            case SUBTRACT, MINUS -> adjustSpeed(-1);
            default -> { return; }
        }
        event.consume();
    }

    private void moveSelection(int amount) {
        int selected = pointList.getSelectionModel().getSelectedIndex();
        if (selected < 0) selected = Math.max(0, controller.selectedIndexProperty().get());
        selectPoint(Math.max(0, Math.min(controller.samples().size() - 1, selected + amount)));
    }

    private void selectPoint(int index) { controller.seek(index); }

    private void announceSelectedPoint() {
        int selected = pointList.getSelectionModel().getSelectedIndex();
        if (selected < 0) selected = Math.max(0, controller.selectedIndexProperty().get());
        controller.announcePoint(selected);
    }

    private void adjustSpeed(int direction) {
        List<Double> speeds = speedSelector.getItems();
        int current = speeds.indexOf(speedSelector.getValue());
        int next = Math.max(0, Math.min(speeds.size() - 1, current + direction));
        speedSelector.setValue(speeds.get(next));
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("screen-title");
        return label;
    }

    private static Label sectionHeading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static void addField(GridPane grid, int row, String label, javafx.scene.Node control) {
        Label title = new Label(label);
        title.getStyleClass().add("field-label");
        grid.add(title, 0, row);
        grid.add(control, 1, row);
    }
}
