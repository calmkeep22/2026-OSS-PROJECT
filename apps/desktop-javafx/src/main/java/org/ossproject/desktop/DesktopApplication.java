package org.ossproject.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.ossproject.accessibility.notification.*;
import org.ossproject.accessibility.port.SoundPort;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;
import org.ossproject.accessibility.infrastructure.sound.ToneSoundAdapter;
import org.ossproject.accessibility.infrastructure.speech.SpeechAdapterFactory;
import org.ossproject.finance.model.*;
import org.ossproject.application.usecase.OrderUseCase;
import org.ossproject.application.usecase.PortfolioUseCase;
import org.ossproject.desktop.chart.AccessibleChartController;
import org.ossproject.desktop.chart.AccessibleChartView;
import org.ossproject.desktop.presentation.Formatters;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.mocktrading.InMemoryMockTradingAdapter;
import org.ossproject.anomaly.AnomalyAlert;
import org.ossproject.anomaly.RuleBasedAnomalyDetector;
import org.ossproject.sonification.infrastructure.sound.PcmGraphSonificationAdapter;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class DesktopApplication extends Application {
    private enum Screen {
        DASHBOARD("대시보드"), STOCKS("종목"), ACCOUNT("계좌"), RADIO("청각 차트"), ANOMALY("이상 감지"),
        PATTERNS("유사 패턴"), NEWS("뉴스 · 챗봇"), SETTINGS("음성 · 설정");
        private final String label;
        Screen(String label) { this.label = label; }
    }

    private final InMemoryMockTradingAdapter tradingAdapter = new InMemoryMockTradingAdapter();
    private final FakeStockQueryAdapter stockAdapter = new FakeStockQueryAdapter();
    private final RuleBasedAnomalyDetector anomalyDetector = new RuleBasedAnomalyDetector();
    private final PortfolioUseCase portfolioUseCase = new PortfolioUseCase(tradingAdapter);
    private final OrderUseCase orderUseCase = new OrderUseCase(tradingAdapter);
    private final SpeechPort speechPort = SpeechAdapterFactory.create();
    private final SpeechQueue speechQueue = new SpeechQueue(speechPort, defaultSpeechOptions());
    private final SoundPort soundPort = new ToneSoundAdapter();
    private AccessibleChartController accessibleChartController;
    private AccessibleChartView accessibleChartView;
    private final Label status = new Label("준비됨");
    private final StackPane screenHost = new StackPane();
    private final Map<Screen, Button> navigationButtons = new EnumMap<>(Screen.class);
    private BorderPane root;
    private boolean speechEnabled;
    private boolean soundEnabled = true;
    private boolean keyboardGuidanceEnabled = true;
    private boolean reducedMotionEnabled = true;
    private boolean largeTextEnabled = true;
    private boolean highContrastEnabled;
    private String informationDensity = "표준";

    private static SpeechOptions defaultSpeechOptions() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win")
                ? SpeechOptions.DEFAULT.withVoiceName("Microsoft Heami Desktop")
                : SpeechOptions.DEFAULT;
    }

    @Override public void start(Stage stage) {
        root = new BorderPane();
        root.getStyleClass().addAll("app-root", "large-text");
        root.setLeft(createSidebar());
        root.setCenter(screenHost);
        root.setBottom(createStatusBar());
        accessibleChartController = new AccessibleChartController(
                stockAdapter,
                new PcmGraphSonificationAdapter(),
                (text, key) -> announce(text, SpeechPriority.USER_REQUEST, key),
                status::setText);
        accessibleChartView = new AccessibleChartView(accessibleChartController);
        speechQueue.addListener(new SpeechListener() {
            @Override public void onStarted(SpeechRequest request) {
                accessibleChartController.setSpeechActive(true);
            }

            @Override public void onCompleted(SpeechRequest request) {
                accessibleChartController.setSpeechActive(false);
            }

            @Override public void onFailed(SpeechRequest request, RuntimeException error) {
                accessibleChartController.setSpeechActive(false);
                Platform.runLater(() -> {
                    status.setText("음성 출력 실패: " + error.getMessage());
                    play(SoundCue.ERROR);
                });
            }

            @Override public void onInterrupted(SpeechRequest request) {
                accessibleChartController.setSpeechActive(false);
                Platform.runLater(() -> status.setText("음성 안내 중단: " + request.text()));
            }
        });

        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(getClass().getResource("/styles/application.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.DASHBOARD));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.A, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.ACCOUNT));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.SETTINGS));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.RADIO));

        navigate(Screen.DASHBOARD);
        stage.setTitle("접근성 투자 정보 도구 - 모의주문 모드");
        stage.setMinWidth(1000); stage.setMinHeight(700);
        stage.setScene(scene); stage.show();
    }

    private VBox createSidebar() {
        Label product = new Label("투자 접근성\n프로그램");
        product.getStyleClass().add("sidebar-title");
        Label mode = new Label("모의주문 모드");
        mode.getStyleClass().add("mode-badge");
        VBox nav = new VBox(8);
        for (Screen screen : Screen.values()) {
            Button button = new Button(screen.label);
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAccessibleText(screen.label + " 화면 열기");
            button.setOnAction(event -> navigate(screen));
            navigationButtons.put(screen, button);
            nav.getChildren().add(button);
        }
        Region grow = new Region(); VBox.setVgrow(grow, Priority.ALWAYS);
        Label help = new Label("키보드 안내\nAlt+D 대시보드\nAlt+A 계좌\nAlt+R 청각 차트\nAlt+S 설정");
        help.getStyleClass().add("keyboard-help");
        VBox sidebar = new VBox(14, product, mode, nav, grow, help);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(24));
        sidebar.setPrefWidth(232); sidebar.setMinWidth(232);
        return sidebar;
    }

    private void navigate(Screen screen) {
        navigationButtons.forEach((key, button) -> button.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("selected"), key == screen));
        Node content = switch (screen) {
            case DASHBOARD -> createDashboard();
            case ACCOUNT -> createAccountScreen();
            case RADIO -> accessibleChartView.root();
            case SETTINGS -> createSettingsScreen();
            case ANOMALY -> createAnomalyScreen();
            case STOCKS -> createStockScreen();
            case PATTERNS -> createPlaceholder("유사 패턴", "일봉 차트의 유사 패턴 분석은 후속 단계에서 연결됩니다.");
            case NEWS -> createPlaceholder("뉴스 · 챗봇", "뉴스 목록, AI 요약과 요약 듣기를 다음 단계에서 연결합니다.");
        };
        screenHost.getChildren().setAll(content);
        content.requestFocus();
        status.setText(screen.label + " 화면");
    }

    private ScrollPane createDashboard() {
        Label title = heading("오늘의 시장 상황");
        HBox market = new HBox(18,
                marketCard("국내지수", "상승", "코스피 +0.8%", "market-up"),
                marketCard("해외 지수", "보합", "S&P 500 0.0%", "market-flat"),
                marketCard("금리 환율", "하락", "원/달러 -0.3%", "market-down"));
        market.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));

        Label functionsTitle = heading("주요 기능");
        GridPane grid = new GridPane(); grid.setHgap(20); grid.setVgap(20);
        addFeature(grid, 0, 0, "종목", "관심 종목 · 시세", Screen.STOCKS);
        addFeature(grid, 1, 0, "계좌", "자산 현황 · 모의주문", Screen.ACCOUNT);
        addFeature(grid, 2, 0, "청각 차트", "그래프 요약 · 연속음 탐색", Screen.RADIO);
        addFeature(grid, 0, 1, "이상 감지", "가격 · 거래량 알림", Screen.ANOMALY);
        addFeature(grid, 1, 1, "유사 패턴", "차트 패턴 추천", Screen.PATTERNS);
        addFeature(grid, 2, 1, "뉴스 · 챗봇", "AI 브리핑", Screen.NEWS);
        addFeature(grid, 0, 2, "음성 · 설정", "음성 안내 · 모드 전환", Screen.SETTINGS);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(33.333); grid.getColumnConstraints().add(column);
        }
        VBox body = new VBox(26, title, market, functionsTitle, grid);
        body.getStyleClass().add("screen-content"); body.setPadding(new Insets(32));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); scroll.setAccessibleText("대시보드");
        return scroll;
    }

    private VBox marketCard(String title, String direction, String value, String style) {
        Label name = new Label(title); name.getStyleClass().add("card-title");
        Label metric = new Label(direction + "  " + value); metric.setWrapText(true);
        VBox card = new VBox(12, name, metric); card.getStyleClass().addAll("market-card", style);
        card.setAccessibleText(title + ", " + direction + ", " + value);
        return card;
    }

    private void addFeature(GridPane grid, int column, int row, String title, String description, Screen screen) {
        Button card = new Button();
        Label titleLabel = new Label(title); titleLabel.getStyleClass().add("feature-title");
        Label detail = new Label(description); detail.getStyleClass().add("feature-detail");
        VBox content = new VBox(12, titleLabel, detail); content.setAlignment(Pos.CENTER);
        card.setGraphic(content); card.getStyleClass().add("feature-card");
        card.setAccessibleText(title + ", " + description + " 화면 열기");
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); card.setOnAction(event -> navigate(screen));
        grid.add(card, column, row); GridPane.setHgrow(card, Priority.ALWAYS); GridPane.setVgrow(card, Priority.ALWAYS);
    }

    private ScrollPane createAccountScreen() {
        PortfolioSnapshot snapshot = portfolioUseCase.loadPortfolio();
        Label title = heading("계좌 · 모의주문");
        Label notice = new Label("실제 주문이 전송되지 않는 개발용 모의주문 모드입니다.");
        notice.getStyleClass().add("safety-note");
        SplitPane split = new SplitPane(createPortfolio(snapshot), createOrderForm()); split.setDividerPositions(0.58);
        VBox body = new VBox(18, title, notice, split); VBox.setVgrow(split, Priority.ALWAYS);
        body.getStyleClass().add("screen-content"); body.setPadding(new Insets(32));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); scroll.setFitToHeight(true);
        return scroll;
    }

    private ScrollPane createStockScreen() {
        StockDetail detail = stockAdapter.getDetail("005930");
        Label title = heading(detail.name());
        Label symbol = new Label(detail.symbol()); symbol.getStyleClass().add("mode-badge");
        HBox titleRow = new HBox(12, title, symbol); titleRow.setAlignment(Pos.CENTER_LEFT);
        String direction = detail.direction() == PriceDirection.UP ? "상승" : detail.direction() == PriceDirection.DOWN ? "하락" : "보합";
        Label price = new Label(Formatters.won(detail.currentPrice()) + " · " + direction + " "
                + Formatters.won(detail.changeAmount().abs()) + " · " + detail.changeRate().abs() + "%");
        price.getStyleClass().add("stock-price");
        Label metrics = new Label("시가 " + Formatters.won(detail.open()) + " · 고가 " + Formatters.won(detail.high())
                + " · 저가 " + Formatters.won(detail.low()) + " · 거래량 " + detail.volume());
        metrics.setWrapText(true);
        Button listen = new Button("최신 정보 듣기");
        listen.setOnAction(event -> announce(detail.name() + " 현재가 " + Formatters.won(detail.currentPrice())
                + ", 전일 대비 " + direction + " " + detail.changeRate().abs() + "퍼센트입니다.",
                SpeechPriority.USER_REQUEST, "stock-detail-" + detail.symbol()));

        HBox periods = new HBox(8);
        ToggleGroup periodGroup = new ToggleGroup();
        for (PricePeriod period : PricePeriod.values()) {
            ToggleButton button = new ToggleButton(period.displayName()); button.setToggleGroup(periodGroup);
            if (period == PricePeriod.MONTH) button.setSelected(true); periods.getChildren().add(button);
        }

        TableView<PricePoint> history = new TableView<>(FXCollections.observableArrayList(
                stockAdapter.getPriceHistory(detail.symbol(), PricePeriod.MONTH)));
        history.setAccessibleText("삼성전자 최근 가격 흐름 표");
        history.setAccessibleHelp("차트와 동일한 날짜별 시가, 고가, 저가, 종가와 거래량입니다.");
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        history.getColumns().add(priceColumn("날짜", point -> point.date().toString()));
        history.getColumns().add(priceColumn("시가", point -> Formatters.won(point.open())));
        history.getColumns().add(priceColumn("고가", point -> Formatters.won(point.high())));
        history.getColumns().add(priceColumn("저가", point -> Formatters.won(point.low())));
        history.getColumns().add(priceColumn("종가", point -> Formatters.won(point.close())));
        history.getColumns().add(priceColumn("거래량", point -> Long.toString(point.volume())));
        VBox.setVgrow(history, Priority.ALWAYS);
        VBox body = new VBox(16, titleRow, price, metrics, listen, periods, sectionHeading("최근 1개월 가격 흐름"), history);
        body.getStyleClass().add("screen-content"); body.setPadding(new Insets(32));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); scroll.setFitToHeight(true); return scroll;
    }

    private TableColumn<PricePoint, String> priceColumn(String title, java.util.function.Function<PricePoint, String> mapper) {
        TableColumn<PricePoint, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue()))); return column;
    }

    private VBox createPortfolio(PortfolioSnapshot snapshot) {
        Label summary = new Label("총 평가금액 " + Formatters.won(snapshot.totalMarketValue())
                + " · 주문 가능 현금 " + Formatters.won(snapshot.cash())); summary.setWrapText(true);
        TableView<Holding> table = new TableView<>(FXCollections.observableArrayList(snapshot.holdings()));
        table.setAccessibleText("보유 종목 표"); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(column("종목", h -> h.name() + ", 코드 " + h.symbol()));
        table.getColumns().add(column("수량", h -> h.quantity() + "주"));
        table.getColumns().add(column("현재가", h -> Formatters.won(h.currentPrice())));
        table.getColumns().add(column("평가손익", h -> (h.profitLoss().signum() >= 0 ? "이익 " : "손실 ")
                + Formatters.won(h.profitLoss().abs())));
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox box = new VBox(12, sectionHeading("자산 현황"), summary, table); box.setPadding(new Insets(18));
        return box;
    }

    private TableColumn<Holding, String> column(String title, java.util.function.Function<Holding, String> mapper) {
        TableColumn<Holding, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue()))); return column;
    }

    private VBox createOrderForm() {
        TextField symbol = new TextField("005930"); TextField name = new TextField("삼성전자");
        ComboBox<OrderSide> side = new ComboBox<>(FXCollections.observableArrayList(OrderSide.values())); side.setValue(OrderSide.BUY);
        side.setConverter(new StringConverter<>() {
            @Override public String toString(OrderSide value) { return value == null ? "" : value.displayName(); }
            @Override public OrderSide fromString(String value) { return OrderSide.valueOf(value); }
        });
        Spinner<Integer> quantity = new Spinner<>(1, 1_000_000, 1); quantity.setEditable(true);
        TextField price = new TextField("73500");
        GridPane form = new GridPane(); form.setHgap(10); form.setVgap(10);
        addField(form, 0, "종목 코드", symbol); addField(form, 1, "종목명", name);
        addField(form, 2, "주문 구분", side); addField(form, 3, "수량", quantity); addField(form, 4, "지정가", price);
        Button preview = new Button("주문 내용 검토"); preview.setDefaultButton(true);
        preview.setAccessibleHelp("주문을 제출하지 않고 재확인 창을 엽니다.");
        preview.setOnAction(event -> previewOrder(symbol, name, side, quantity, price));
        VBox box = new VBox(14, sectionHeading("모의주문 준비"), form, preview); box.setPadding(new Insets(18));
        return box;
    }

    private VBox createAnomalyScreen() {
        StockDetail stock = stockAdapter.getDetail("005930");
        java.util.List<AnomalyAlert> alerts = anomalyDetector.detect(stock.symbol(), stock.name(),
                stockAdapter.getPriceHistory(stock.symbol(), PricePeriod.MONTH));
        String primaryText = alerts.isEmpty()
                ? "현재 규칙에 해당하는 이상 신호가 없습니다."
                : "방금 · " + alerts.get(0).stockName() + " · " + alerts.get(0).explanation();
        Label notice = new Label(primaryText);
        notice.getStyleClass().add("anomaly-high"); notice.setWrapText(true);
        Button listen = new Button("소리로 듣기");
        listen.setOnAction(event -> announce(notice.getText(), SpeechPriority.ALERT, "sample-anomaly"));
        java.util.List<String> rows = alerts.stream()
                .map(alert -> alert.stockName() + " · " + alert.explanation() + " · "
                        + (alert.severity() == org.ossproject.anomaly.AnomalySeverity.HIGH ? "높음" : "보통"))
                .toList();
        ListView<String> history = new ListView<>(FXCollections.observableArrayList(rows));
        VBox body = new VBox(20, heading("이상 감지"), notice, listen, sectionHeading("오늘의 감지 기록"), history);
        body.getStyleClass().add("screen-content"); body.setPadding(new Insets(32)); VBox.setVgrow(history, Priority.ALWAYS);
        return body;
    }

    private ScrollPane createSettingsScreen() {
        CheckBox tts = setting("화면 읽기(TTS)", speechEnabled, selected -> {
            speechEnabled = selected;
            if (selected) announce("음성 안내를 시작합니다.", SpeechPriority.USER_REQUEST, "speech-enabled");
            else speechQueue.clear();
        });
        CheckBox keyboard = setting("키보드 탐색 안내", keyboardGuidanceEnabled,
                selected -> keyboardGuidanceEnabled = selected);
        CheckBox reducedMotion = setting("애니메이션 줄이기", reducedMotionEnabled,
                selected -> reducedMotionEnabled = selected);
        CheckBox anomalySound = setting("이상 감지 소리", soundEnabled, selected -> soundEnabled = selected);
        CheckBox largeText = setting("큰 글자", largeTextEnabled, selected -> {
            largeTextEnabled = selected;
            toggleClass("large-text", selected);
        });
        CheckBox contrast = setting("고대비", highContrastEnabled, selected -> {
            highContrastEnabled = selected;
            toggleClass("high-contrast", selected);
        });
        ComboBox<SpeechVoice> voice = new ComboBox<>();
        SpeechVoice systemDefault = new SpeechVoice("", "시스템 기본 음성", "");
        voice.getItems().add(systemDefault); voice.setValue(systemDefault);
        voice.setConverter(new StringConverter<>() {
            @Override public String toString(SpeechVoice value) {
                if (value == null) return "";
                return value.language().isBlank() ? value.displayName() : value.displayName() + " (" + value.language() + ")";
            }
            @Override public SpeechVoice fromString(String value) { return systemDefault; }
        });
        voice.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null || speechQueue.isClosed()) return;
            speechQueue.setOptions(speechQueue.options().withVoiceName(selected.id().isBlank() ? null : selected.id()));
        });
        loadVoices(voice, systemDefault);

        ComboBox<String> speed = new ComboBox<>(FXCollections.observableArrayList("0.8배", "1.0배", "1.2배", "1.5배"));
        speed.setValue(String.format(java.util.Locale.ROOT, "%.1f배", speechQueue.options().rate()));
        speed.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null && !speechQueue.isClosed()) {
                double rate = Double.parseDouble(selected.replace("배", ""));
                speechQueue.setOptions(speechQueue.options().withRate(rate));
            }
        });
        Slider volume = new Slider(0, 100, speechQueue.options().volume()); volume.setShowTickLabels(true); volume.setMajorTickUnit(25);
        volume.valueProperty().addListener((obs, old, selected) -> {
            if (!speechQueue.isClosed()) speechQueue.setOptions(speechQueue.options().withVolume(selected.intValue()));
        });
        ComboBox<String> density = new ComboBox<>(FXCollections.observableArrayList("간단히", "표준", "자세히"));
        density.setValue(informationDensity);
        density.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) informationDensity = selected;
        });
        GridPane voiceSettings = new GridPane(); voiceSettings.setHgap(16); voiceSettings.setVgap(10);
        addField(voiceSettings, 0, "음성", voice);
        addField(voiceSettings, 1, "속도", speed); addField(voiceSettings, 2, "음량", volume);
        addField(voiceSettings, 3, "정보량", density);
        Button preview = new Button("설정 미리 듣기");
        preview.setOnAction(event -> {
            boolean before = speechEnabled; speechEnabled = true;
            announce("음성 설정 미리 듣기입니다. 현재 속도는 " + speed.getValue() + "이고 정보량은 " + density.getValue() + "입니다.",
                    SpeechPriority.USER_REQUEST, "settings-preview"); speechEnabled = before || tts.isSelected();
        });
        VBox settings = new VBox(8, tts, keyboard, reducedMotion, anomalySound, largeText, contrast);
        settings.getStyleClass().add("settings-card");
        VBox body = new VBox(22, heading("설정"), sectionHeading("접근성"), preview, settings,
                sectionHeading("음성 설정"), voiceSettings);
        body.getStyleClass().add("screen-content"); body.setPadding(new Insets(32));
        ScrollPane scroll = new ScrollPane(body); scroll.setFitToWidth(true); return scroll;
    }

    private void loadVoices(ComboBox<SpeechVoice> voiceBox, SpeechVoice systemDefault) {
        if (!(speechPort instanceof SpeechVoiceProvider provider)) return;
        CompletableFuture.supplyAsync(provider::availableVoices).whenComplete((voices, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        status.setText("음성 목록을 불러오지 못했습니다. 시스템 기본 음성을 사용합니다.");
                        return;
                    }
                    String selectedVoice = speechQueue.options().voiceName();
                    SpeechVoice selected = voices.stream()
                            .filter(item -> java.util.Objects.equals(item.id(), selectedVoice))
                            .findFirst().orElse(systemDefault);
                    voiceBox.getItems().setAll(systemDefault);
                    voiceBox.getItems().addAll(voices);
                    voiceBox.setValue(selected);
                }));
    }

    private CheckBox setting(String text, boolean selected, java.util.function.Consumer<Boolean> action) {
        CheckBox check = new CheckBox(text); check.setSelected(selected); check.getStyleClass().add("setting-toggle");
        check.selectedProperty().addListener((obs, old, value) -> action.accept(value)); return check;
    }

    private TextField disabledValue(String value) { TextField field = new TextField(value); field.setEditable(false); return field; }

    private VBox createPlaceholder(String title, String message) {
        Label copy = new Label(message); copy.setWrapText(true);
        VBox box = new VBox(20, heading(title), copy); box.getStyleClass().add("screen-content"); box.setPadding(new Insets(32)); return box;
    }

    private Label heading(String text) { Label label = new Label(text); label.getStyleClass().add("title"); return label; }
    private Label sectionHeading(String text) { Label label = new Label(text); label.getStyleClass().add("section-title"); return label; }

    private void addField(GridPane form, int row, String labelText, Control control) {
        Label label = new Label(labelText); label.setLabelFor(control); control.setMaxWidth(Double.MAX_VALUE);
        form.add(label, 0, row); form.add(control, 1, row); GridPane.setHgrow(control, Priority.ALWAYS);
    }

    private void previewOrder(TextField symbol, TextField name, ComboBox<OrderSide> side,
                              Spinner<Integer> quantity, TextField price) {
        try {
            OrderRequest request = new OrderRequest(symbol.getText().trim(), name.getText().trim(), side.getValue(),
                    quantity.getValue(), new BigDecimal(price.getText().replace(",", "").trim()));
            OrderPreview result = orderUseCase.preview(request);
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("모의주문 재확인");
            confirmation.setHeaderText(request.name() + " " + request.quantity() + "주 " + request.side().displayName() + " 주문을 제출하시겠습니까?");
            confirmation.setContentText("종목 코드: " + request.symbol() + "\n지정가: " + Formatters.won(request.limitPrice())
                    + "\n예상 주문금액: " + Formatters.won(result.estimatedAmount())
                    + "\n주문 후 예상 현금: " + Formatters.won(result.estimatedCashAfterOrder()) + "\n\n실제 주문이 아닌 모의주문입니다.");
            ButtonType submit = new ButtonType("모의 " + request.side().displayName() + " 제출", ButtonBar.ButtonData.OK_DONE);
            confirmation.getButtonTypes().setAll(submit, ButtonType.CANCEL);
            confirmation.showAndWait().filter(submit::equals).ifPresent(button -> {
                OrderReceipt receipt = orderUseCase.submitConfirmed(request);
                status.setText(receipt.statusMessage() + " 주문번호 " + receipt.orderId());
                announce(receipt.statusMessage(), SpeechPriority.ORDER, "order-" + receipt.orderId()); play(SoundCue.SUCCESS);
            });
        } catch (RuntimeException exception) {
            status.setText("주문 입력 오류: " + exception.getMessage());
            announce("주문 입력 오류. " + exception.getMessage(), SpeechPriority.CRITICAL, "order-input-error"); play(SoundCue.ERROR);
            Alert alert = new Alert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK); alert.setHeaderText("주문 입력을 확인하세요."); alert.showAndWait();
        }
    }

    private HBox createStatusBar() {
        status.setWrapText(true); HBox bar = new HBox(status); bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(10, 16, 10, 16)); return bar;
    }

    private void announce(String text, SpeechPriority priority, String key) {
        announce(text, priority, key, SpeechMergePolicy.KEEP_FIRST);
    }
    private void announce(String text, SpeechPriority priority, String key, SpeechMergePolicy mergePolicy) {
        if (speechEnabled && !speechQueue.isClosed()) {
            speechQueue.announce(new SpeechRequest(text, priority, key, mergePolicy));
        }
    }
    private void play(SoundCue cue) { if (soundEnabled) soundPort.play(cue); }
    private void toggleClass(String name, boolean enabled) {
        if (enabled && !root.getStyleClass().contains(name)) root.getStyleClass().add(name);
        if (!enabled) root.getStyleClass().remove(name);
    }

    @Override public void stop() {
        if (accessibleChartController != null) accessibleChartController.close();
        speechQueue.close();
        soundPort.close();
    }
    public static void main(String[] args) { launch(args); }
}
