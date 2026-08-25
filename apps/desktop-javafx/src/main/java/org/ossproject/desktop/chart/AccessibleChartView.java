package org.ossproject.desktop.chart;

import javafx.collections.FXCollections;
import javafx.beans.binding.Bindings;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.ossproject.sonification.playback.GraphPlaybackState;
import org.ossproject.sonification.model.GraphScaleMode;
import org.ossproject.desktop.chart.AccessibleChartController.ListeningPeriod;

import java.util.List;
import java.util.Objects;

/** Builds the JavaFX controls for the accessible chart and delegates all behavior to its controller. */
public final class AccessibleChartView {
    private final AccessibleChartController controller;
    private final ScrollPane root;
    private final ComboBox<Double> speedSelector;

    public AccessibleChartView(AccessibleChartController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");

        Label title = heading("청각 차트");
        var stockDetail = controller.stock();
        Label stock = new Label();
        stock.textProperty().bind(Bindings.concat(stockDetail.name(), " (", stockDetail.symbol(),
                ") · ", controller.seriesDescriptionProperty()));
        stock.getStyleClass().add("section-title");
        Label summary = new Label();
        summary.textProperty().bind(controller.summaryTextProperty());
        summary.setWrapText(true);
        summary.getStyleClass().add("chart-summary");
        summary.accessibleTextProperty().bind(Bindings.concat(
                "차트 전체 요약. ", controller.summaryTextProperty()));
        Button listenSummary = new Button("전체 요약 듣기 (S)");
        listenSummary.setOnAction(event -> controller.announceSummary());
        HBox summaryRow = new HBox(12, summary, listenSummary);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summary.setMinWidth(0);
        summary.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summary, Priority.ALWAYS);
        // 줄어드는 쪽은 글이어야 한다. 글은 접혀도 읽히지만, 눌린 단추는 "..." 만
        // 남아 무엇을 누르는 단추인지 알 수 없다. 큰 글자에서 실제로 그렇게 됐다.
        pin(listenSummary);

        Label playbackState = new Label();
        playbackState.textProperty().bind(controller.playbackStatusProperty());
        playbackState.getStyleClass().add("radio-status");
        playbackState.setWrapText(true);
        playbackState.setFocusTraversable(true);
        playbackState.setAccessibleHelp("좌우 또는 위아래 방향키로 가격 지점을 이동합니다. "
                + "Space 키로 재생하거나 일시정지합니다.");
        playbackState.addEventFilter(KeyEvent.KEY_PRESSED, this::handleNavigationKey);

        ComboBox<ListeningPeriod> periodSelector = createPeriodSelector();
        Label periodLabel = new Label("듣는 기간");
        periodLabel.setLabelFor(periodSelector);
        HBox periodControls = new HBox(12, periodLabel, periodSelector);
        periodControls.setAlignment(Pos.CENTER_LEFT);
        HBox seriesRow = new HBox(18, stock, periodControls);
        seriesRow.setAlignment(Pos.CENTER_LEFT);
        stock.setMinWidth(0);
        stock.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(stock, Priority.ALWAYS);
        // 제목은 접지 않는다. 이 줄은 세로 스크롤이 있는 칸 안에 있어서, 글이 접히면
        // 높이가 바뀌고 → 스크롤막대가 생겼다 사라지고 → 폭이 바뀌어 다시 접힌다.
        // 실제로 화면 스레드가 그 되풀이에 걸려 굳었다. 제목이 줄어들어도 잃는 것은
        // 없다 — 바로 아래 요약 상자가 같은 문장을 온전히 다시 적는다.
        pin(periodControls);

        SonificationBarChart barChart = new SonificationBarChart(
                controller.playbackSamples(), controller::seekPlaybackPoint);
        controller.selectedIndexProperty().addListener((obs, old, selected) ->
                barChart.select(controller.playbackIndexForSourceIndex(selected.intValue())));

        Button playChart = new Button("재생 (Space)");
        playChart.setAccessibleHelp("원본 지점은 유지하고 핵심 굴곡은 최대 48개로 줄여 약 12초 동안 연속음으로 재생합니다.");
        playChart.setOnAction(event -> {
            controller.play();
            // 마우스로 재생을 눌러도 좌우 키가 버튼 사이를 돌지 않고 곧바로 가격을 탐색한다.
            Platform.runLater(playbackState::requestFocus);
        });
        Button pauseChart = new Button("일시정지");
        pauseChart.setOnAction(event -> {
            controller.pause();
            controller.showCurrentPointStatus();
            playbackState.requestFocus();
        });
        Button replayChart = new Button("처음부터 다시 듣기 (R)");
        replayChart.setOnAction(event -> controller.replay());
        // 세 단추를 한 줄에 같은 폭으로 편다. 재생·일시정지·다시 듣기는 서로 짝이라
        // 크기가 같아야 짝으로 읽히고, 넓게 벌려 두면 손과 눈이 찾기 쉽다.
        //
        // 줄어들지는 않는다. 좁아져 글자가 "..." 로 바뀌면 무엇을 누르는 단추인지
        // 사라진다 — 큰 글자 모드에서 실제로 그랬다.
        HBox playbackControls = new HBox(10, playChart, pauseChart, replayChart);
        playbackControls.setAlignment(Pos.CENTER_LEFT);
        pin(playChart, pauseChart, replayChart);
        for (Button control : List.of(playChart, pauseChart, replayChart)) {
            control.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(control, Priority.ALWAYS);
        }
        playbackState.setMinWidth(0);
        playbackState.setMaxWidth(Double.MAX_VALUE);
        // 상태 문구가 위, 단추가 아래. 나란히 두면 문구가 좁아져 접히고, 단추도 좁아진다.
        VBox playbackRow = new VBox(10, playbackState, playbackControls);

        ComboBox<GraphScaleMode> scale = createScaleSelector();
        Label mapping = new Label();
        mapping.textProperty().bind(controller.scaleDescriptionProperty());
        mapping.setWrapText(true);
        ComboBox<Double> percentRange = createPercentRangeSelector(scale);
        speedSelector = createSpeedSelector();
        // 설정 화면과 같은 방식으로 맞춘다. 그쪽에서 먼저 드러난 문제다 — addField 는
        // 칸을 무한히 넓히므로, 눈금 글자를 켠 슬라이더가 왼쪽 제목 칸을 가로질러
        // 카드 끝까지 그려지고 아래 행까지 침범했다. 폭을 정하고 현재 값은 옆의
        // 퍼센트 글자로 보여 준다.
        Slider volume = new Slider(0, 100, controller.volume() * 100);
        volume.getStyleClass().add("volume-slider");
        volume.setShowTickLabels(false);
        volume.setShowTickMarks(false);
        volume.setBlockIncrement(5);
        volume.setAccessibleText("청각 차트 음량");
        Label volumeValue = new Label(Math.round(volume.getValue()) + "%");
        volumeValue.getStyleClass().add("volume-value");
        volume.valueProperty().addListener((obs, old, selected) -> {
            controller.setVolume(selected.doubleValue() / 100.0);
            volumeValue.setText(Math.round(selected.doubleValue()) + "%");
        });
        Label volumeTitle = new Label("음량");
        volumeTitle.setLabelFor(volume);
        HBox volumeControl = new HBox(12, volume, volumeValue);
        volumeControl.setAlignment(Pos.CENTER_LEFT);
        volumeControl.setMinWidth(0);
        // 이제 세 칸 중 한 칸을 채운다. 440 으로 못 박으면 칸 폭과 어긋난다.
        volumeControl.setMaxWidth(Double.MAX_VALUE);
        volumeControl.getStyleClass().add("settings-volume-control");
        HBox.setHgrow(volume, Priority.NEVER);

        // 이름을 왼쪽에 두고 값을 오른쪽에 세우면 네 줄이 세로로 쌓여 화면을 길게
        // 먹는다. 이름을 값 위에 올려 세 칸으로 나란히 편다.
        //
        // 고정 등락 범위는 음역 기준에 딸린 값이라(자동 범위일 때는 쓰이지 않는다)
        // 다른 칸으로 떼지 않고 그 아래에 붙인다. 떼어 놓으면 둘의 관계가 사라진다.
        GridPane options = new GridPane();
        options.setHgap(16);
        options.setVgap(10);
        // 네 칸을 한 줄에 편다. 고정 등락 범위를 음역 기준 아래에 두었더니 둘째 줄이
        // 생겨 설정 상자가 세로로 길어지고, 그만큼 서로 멀어져 보였다.
        //
        // 자리는 음역 기준 바로 옆이다. 이 값은 음역 기준이 "고정" 일 때만 쓰이므로
        // 떨어뜨려 놓으면 왜 흐린지 알 수 없다. 붙여 두면 흐려진 이유가 옆에 있다.
        options.getColumnConstraints().addAll(
                optionColumn(), optionColumn(), optionColumn(), optionColumn());
        options.add(optionField("음역 기준", scale), 0, 0);
        options.add(optionField("고정 등락 범위", percentRange), 1, 0);
        options.add(optionField("재생 속도", speedSelector), 2, 0);
        options.add(optionField("음량", volumeControl), 3, 0);
        volumeTitle.setVisible(false);
        volumeTitle.setManaged(false);
        options.add(mapping, 0, 1, 4, 1);

        // 설정을 상자 안에 담는다. 상자가 없으면 어디까지가 "재생 설정" 인지 경계가 없다.
        //
        // 폭은 위쪽 상자들과 맞춘다. 여기만 좁히면 같은 화면에서 상자마다 오른쪽 끝이
        // 달라져, 세로로 훑을 때 줄이 맞지 않는다.
        VBox optionsCard = new VBox(options);
        optionsCard.getStyleClass().add("settings-card");
        optionsCard.setMaxWidth(Double.MAX_VALUE);

        Label keyboardHelp = new Label("키보드: ←/→ 한 지점 · Ctrl+←/→ 세 지점 · "
                + "Enter 그 지점의 정확한 값 · Space 재생/일시정지 · R 다시 듣기 · S 요약");
        keyboardHelp.getStyleClass().add("keyboard-help");
        keyboardHelp.setWrapText(true);

        periodSelector.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null || selected == controller.listeningPeriod()) return;
            periodSelector.setDisable(true);
            controller.setListeningPeriod(selected).whenComplete((ignored, failure) ->
                    Platform.runLater(() -> {
                        periodSelector.setDisable(false);
                        if (failure != null) {
                            periodSelector.setValue(controller.listeningPeriod());
                            return;
                        }
                        barChart.setSamples(controller.playbackSamples());
                        playbackState.requestFocus();
                    }));
        });

        VBox body = new VBox(18, title, seriesRow,
                sectionHeading("1. 전체 요약"), summaryRow,
                sectionHeading("2. 전체 그래프 듣기"), barChart, playbackRow, keyboardHelp,
                sectionHeading("재생 설정"), optionsCard);
        body.getStyleClass().add("screen-content");
        body.setPadding(new Insets(32));
        root = new ScrollPane(body);
        root.setFitToWidth(true);
        // 재생 설정까지 필요한 높이를 유지하고, 큰 글자에서는 스크롤한다.
        root.setFitToHeight(false);
        // 칸 자체는 초점을 받지 않는다. 키는 창에서 받으므로 여기 초점이 올 이유가 없다.
        //
        // 눌러서 초점이 들어오면 ScrollPane 은 파란 테두리를 화면 전체에 두른다. 그것은
        // "무엇이 선택되었다" 는 뜻으로 읽히는데 실제로 선택된 것은 없다 — 조작할 수
        // 없는 칸이라서다. 오히려 진짜 초점이 어디 있는지를 헷갈리게 한다.
        //
        // 인라인으로 건다. 테마마다 이 칸의 배경을 다르게 칠하고 있어, 스타일시트에
        // 규칙을 더하면 어느 쪽이 이길지가 테마에 따라 달라진다. 배경은 바깥 칸이
        // 칠하므로 비워 두어도 빈자리가 생기지 않는다.
        //
        // 조작하는 것들의 초점 표시는 그대로다. 단추·고르개·목록은 지금처럼 또렷하게
        // 표시되어야 한다 — 그것이 키보드만 쓰는 사용자의 위치 감각 전부다.
        root.setFocusTraversable(false);
        root.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        root.setAccessibleText("청각 차트 화면");
        root.setAccessibleHelp("이 화면에서는 어디에 있든 키가 듣습니다. "
                + "Space 재생과 멈춤, 좌우 방향키로 지점 이동, Ctrl+좌우로 세 지점씩, "
                + "Enter 그 지점의 정확한 값, R 다시 듣기, S 전체 요약. "
                + "고르개나 글상자 안에서는 그쪽 조작이 먼저입니다.");
        installSpaceToPlay();

        org.ossproject.desktop.view.UiKit.useBrowserLikeScrolling(root);
    }

    public ScrollPane root() { return root; }

    private ComboBox<ListeningPeriod> createPeriodSelector() {
        ComboBox<ListeningPeriod> selector = new ComboBox<>(
                FXCollections.observableArrayList(ListeningPeriod.values()));
        selector.setValue(controller.listeningPeriod());
        selector.setAccessibleText("청각 차트 듣는 기간");
        selector.setPrefWidth(112);
        selector.setMaxWidth(132);
        selector.setConverter(new StringConverter<>() {
            @Override public String toString(ListeningPeriod value) {
                return value == null ? "" : value.label();
            }
            @Override public ListeningPeriod fromString(String value) {
                return ListeningPeriod.MONTH;
            }
        });
        return selector;
    }

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

    /**
     * 화면 어디서든 Space 로 재생하고 멈춘다.
     *
     * <p>원래는 가격 지점 목록이 초점을 쥐고 있을 때만 들었다. 화면에 막 들어오면
     * 초점은 스크롤 칸에 있어서 Space 는 재생이 아니라 스크롤로 갔다 — 듣고 싶어
     * 들어온 화면에서 가장 먼저 누를 키가 아무 일도 안 하는 셈이었다.
     *
     * <p>다만 Space 를 이미 쓰는 컨트롤에서는 비켜선다. 단추 위에서 Space 는 그
     * 단추를 누르는 키이고, 글상자에서는 띄어쓰기다. 그걸 가로채면 화면 전체가
     * 키보드로 못 쓰게 된다.
     */
    private void installSpaceToPlay() {
        // 창(Scene)에 건다. 칸(root)에 걸면 초점이 그 안에 있을 때만 받는데, 화면에 막
        // 들어오면 초점은 종목 고르개나 아무 데도 없다. 그래서 박스 안을 한 번 클릭해야
        // 키가 듣기 시작했다 — 마우스를 쓰지 않는 사용자에게는 시작할 방법이 없었다.
        //
        // 초점을 이쪽으로 끌어오는 방법도 써 봤지만, 화면을 세우는 쪽에서 다시 옮겨
        // 가면서 밀렸다. 초점을 두고 다투는 대신 초점에 기대지 않는다.
        //
        // 이 화면이 창에 붙어 있는 동안만 듣는다. 떼면 반드시 풀어 준다 — 안 그러면
        // 다른 화면에서 누른 R 이 사라진 차트를 되감는다.
        javafx.event.EventHandler<KeyEvent> handler = event -> {
            // Alt 가 눌린 조합은 전역 단축키의 것이다. 여기서 수식키를 보지 않았더니
            // Alt+S 가 S(전체 요약)로, Alt+왼쪽이 왼쪽(이전 지점)으로 처리됐다. 게다가
            // Scene 필터는 accelerator 보다 먼저 돌기 때문에 이 화면에서는 검색과
            // 뒤로가기 단축키가 아예 도달하지 못했다.
            //
            // Ctrl 은 막지 않는다. Ctrl+좌우는 이 화면이 쓰는 조작이다.
            if (event.isAltDown() || event.isMetaDown() || event.isShortcutDown() && !event.isControlDown()) {
                return;
            }
            if (root.getScene() == null || claimedByControl(event.getTarget(), event.getCode())) {
                return;
            }
            handleNavigationKey(event);
        };
        root.sceneProperty().addListener((observed, oldScene, scene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(KeyEvent.KEY_PRESSED, handler);
            }
            if (scene != null) {
                scene.addEventFilter(KeyEvent.KEY_PRESSED, handler);
            }
        });
    }

    /**
     * 그 키가 지금 초점을 쥔 컨트롤의 것인지.
     *
     * <p>처음에는 Space 만 화면 전체에서 받게 했다. 그런데 나머지 키는 그대로 두어,
     * 재생은 되는데 좌우 이동도 R 도 S 도 듣지 않았다. 목록 하나가 초점을 쥐고 있을
     * 때만 듣는 구조였는데, 화면에 들어오면 초점은 스크롤 칸에 있다.
     *
     * <p>그렇다고 전부 가로채면 안 된다. 글상자에서 R 은 글자이고, 고르개에서 좌우는
     * 값 바꾸기이며, 단추 위에서 Space 는 그 단추를 누르는 키다. 그 자리에서는 비켜선다.
     */
    private static boolean claimedByControl(javafx.event.EventTarget target, KeyCode code) {
        for (Object node = target; node instanceof javafx.scene.Node current; node = current.getParent()) {
            // 글상자는 모든 키가 글자다. 여기서는 아무것도 가로채지 않는다.
            if (current instanceof TextInputControl) {
                return true;
            }
            if (current instanceof ButtonBase && (code == KeyCode.SPACE || code == KeyCode.ENTER)) {
                return true;
            }
            // 고르개와 스피너는 위아래로 값을 바꾼다. 좌우는 원래 하는 일이 없으므로
            // 넘겨받는다 — 그러지 않으면 화면에 들어오자마자(초점이 고르개에 있다)
            // 좌우 방향키가 아무 일도 안 하는 것처럼 보인다.
            //
            // Enter 는 목록을 펼친 동안에만 고르개의 것이다. 그때는 고른 것을 확정하는
            // 키다. 닫혀 있을 때는 하는 일이 없으므로 넘겨받는다 — 넘기지 않았더니
            // 화면에 들어오자마자 Enter 가 죽어, 지점 값을 들으려면 차트를 한 번
            // 클릭해야 했다.
            if (current instanceof ComboBoxBase<?> combo) {
                if (code == KeyCode.ENTER) {
                    if (combo.isShowing()) {
                        return true;
                    }
                } else if (CHANGES_VALUE.contains(code)) {
                    return true;
                }
            }
            if ((current instanceof ChoiceBox<?> || current instanceof Spinner<?>)
                    && CHANGES_VALUE.contains(code)) {
                return true;
            }
            // 미끄럼대는 네 방향과 양 끝이 모두 값 조절이다.
            if (current instanceof Slider && MOVES_WITHIN_CONTROL.contains(code)) {
                return true;
            }
            // 목록과 표는 항목 이동이 좌우까지 걸린다. 표는 좌우가 칸 이동이다.
            if ((current instanceof ListView<?> || current instanceof TableView<?>)
                    && MOVES_WITHIN_CONTROL.contains(code)) {
                return true;
            }
            // Space 는 어디서도 양보하지 않는다(글상자와 단추만 빼고). 이 화면에서
            // Space 는 재생이고, 그것이 이 화면에 온 이유다. 고르개에 초점이 있다는
            // 이유로 재생이 안 되면 사용자는 어디에 서 있어야 하는지를 매번 따져야 한다.
            //
            // 고르개는 Alt+아래쪽 화살표로 연다. 표준 조작이라 잃는 것이 없다.
        }
        return false;
    }

    /**
     * 값이나 항목을 옮기는 데 쓰이는 키. 이런 컨트롤 안에서는 그쪽이 먼저다.
     *
     * <p>Space 는 넣지 않는다. 이 화면에서 Space 는 재생이고, 고르개 위에 서 있다고
     * 재생이 멈추면 안 된다. 글상자와 단추에서는 여전히 양보한다 — 거기서 Space 는
     * 띄어쓰기이고 단추 누르기다.
     */
    private static final java.util.Set<KeyCode> MOVES_WITHIN_CONTROL = java.util.EnumSet.of(
            KeyCode.LEFT, KeyCode.RIGHT, KeyCode.UP, KeyCode.DOWN,
            KeyCode.HOME, KeyCode.END, KeyCode.ENTER);

    /**
     * 고르개·스피너가 실제로 쓰는 키.
     *
     * <p>좌우는 넣지 않는다. JavaFX 고르개에서 좌우는 하는 일이 없어, 양보해 봐야
     * 아무 데도 가지 않고 사라진다. 화면에 들어오면 초점이 고르개에 있으므로 그것이
     * 곧 "좌우 방향키가 죽었다" 로 보인다.
     */
    private static final java.util.Set<KeyCode> CHANGES_VALUE = java.util.EnumSet.of(
            KeyCode.UP, KeyCode.DOWN, KeyCode.ENTER);

    private void togglePlayback() {
        if (controller.playbackState() == GraphPlaybackState.PLAYING) {
            controller.pause();
            controller.showCurrentPointStatus();
        } else {
            controller.play();
        }
    }

    /**
     * 이 칸들은 줄어들지 않는다.
     *
     * <p>가로가 모자라면 HBox 는 늘어날 수 있는 자식부터 함께 줄인다. 단추도 그
     * 대상이라 글자가 "..." 로 바뀌는데, 그러면 무엇을 누르는 단추인지 사라진다.
     * 큰 글자 모드에서 "전체 요약 듣기 (S)" 가 실제로 그렇게 됐다.
     *
     * <p>줄어드는 쪽은 언제나 글이어야 한다. 글은 접혀도 읽힌다.
     */
    /** 설정 한 칸. 이름을 값 위에 올린다. */
    private static VBox optionField(String title, javafx.scene.Node control) {
        Label label = new Label(title);
        label.getStyleClass().add("metric-label");
        if (control instanceof Control named) {
            label.setLabelFor(named);
        }
        return new VBox(6, label, control);
    }

    /** 네 칸을 같은 폭으로 나눈다. */
    private static javafx.scene.layout.ColumnConstraints optionColumn() {
        javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
        column.setPercentWidth(25);
        column.setHgrow(Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }

    private static void pin(javafx.scene.layout.Region... regions) {
        for (javafx.scene.layout.Region region : regions) {
            region.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
    }

    private void handleNavigationKey(KeyEvent event) {
        switch (event.getCode()) {
            case LEFT, UP -> moveSelection(event.isControlDown() ? -3 : -1);
            case RIGHT, DOWN -> moveSelection(event.isControlDown() ? 3 : 1);
            case HOME -> selectPoint(0);
            case END -> selectPoint(controller.samples().size() - 1);
            case SPACE -> togglePlayback();
            // 멈춰 선 지점의 정확한 값. 소리로 훑어 대강의 모양을 잡은 뒤, 궁금한
            // 자리에서 숫자를 확인하는 흐름이다. 음높이는 모양을 알려 주지만 값을
            // 알려 주지는 않는다.
            case ENTER -> controller.announcePoint(controller.selectedIndexProperty().get());
            case S -> controller.announceSummary();
            case R -> controller.replay();
            case ADD, PLUS, EQUALS -> adjustSpeed(1);
            case SUBTRACT, MINUS -> adjustSpeed(-1);
            default -> { return; }
        }
        event.consume();
    }

    private void moveSelection(int amount) {
        int selected = controller.selectedIndexProperty().get();
        if (selected < 0) selected = amount > 0 ? -1 : 0;
        selectPoint(Math.max(0, Math.min(controller.samples().size() - 1, selected + amount)));
    }

    private void selectPoint(int index) { controller.seek(index); }

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
