package org.ossproject.desktop;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import javafx.util.Duration;
import org.ossproject.accessibility.notification.*;
import org.ossproject.accessibility.port.SoundPort;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.desktop.composition.DesktopServices;
import org.ossproject.finance.model.*;
import org.ossproject.desktop.chart.AccessibleChartController;
import org.ossproject.desktop.chart.AccessibleChartView;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.desktop.presentation.Formatters;
import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.controller.DesktopScreenController;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.secret.SecretStore;
import org.ossproject.desktop.viewmodel.DesktopSession;
import org.ossproject.desktop.viewmodel.StockSearchViewModel;
import org.ossproject.desktop.viewmodel.ConnectionViewModel;
import org.ossproject.desktop.viewmodel.WatchlistViewModel;
import org.ossproject.desktop.viewmodel.StockDetailViewModel;
import org.ossproject.desktop.viewmodel.ScannerViewModel;
import org.ossproject.desktop.view.screen.SearchScreenView;
import org.ossproject.desktop.view.screen.ConnectionScreenView;
import org.ossproject.desktop.view.screen.WatchlistScreenView;
import org.ossproject.desktop.view.screen.ScannerScreenView;
import org.ossproject.desktop.persistence.DesktopStateRepository;
import org.ossproject.desktop.persistence.DesktopStateSnapshot;
import org.ossproject.desktop.persistence.AccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.SonificationPreferencesRepository;
import org.ossproject.desktop.state.AccessibilityPreferences;
import org.ossproject.desktop.state.AlertRule;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.SonificationPreferences;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.accessibility.AccessibilityAudit;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.ossproject.desktop.view.UiKit.*;

public final class DesktopApplication extends Application {
    private final TradingUseCase tradingUseCase;
    private final MarketApplicationPort marketApplication;
    private final StockQueryPort stockAdapter;
    private final CandleQueryPort candleAdapter;
    private final SpeechPort speechPort;
    private final SpeechQueue speechQueue;
    private final SoundPort soundPort;
    private final SonificationPort sonificationPort;
    private final SecretStore secretStore;
    private AccessibleChartController accessibleChartController;
    private AccessibleChartView accessibleChartView;
    private final Label status = new Label("준비됨");
    private final Label lastDataTime = new Label("마지막 시세 --:--:--");
    private final StackPane screenHost = new StackPane();
    private final Map<Screen, Button> navigationButtons = new EnumMap<>(Screen.class);
    private final DesktopSession session = new DesktopSession();
    private final StockSearchViewModel stockSearchViewModel;
    private final ConnectionViewModel connectionViewModel;
    private final WatchlistViewModel watchlistViewModel;
    private final StockDetailViewModel stockDetailViewModel;
    private final ScannerViewModel scannerViewModel = new ScannerViewModel();
    private final DesktopStateRepository stateRepository;
    private final AccessibilityPreferencesRepository accessibilityPreferencesRepository;
    private final SonificationPreferencesRepository sonificationPreferencesRepository;
    private DesktopScreenController screenController;
    private PauseTransition persistenceDelay;
    private final TextField globalSearch = new TextField();
    private final Button backButton = new Button("← 뒤로");
    private final Label currentLocation = new Label("홈");
    private BorderPane root;
    private VBox sidebarRoot;
    private boolean speechEnabled;
    private boolean soundEnabled = true;
    private boolean keyboardGuidanceEnabled = true;
    private boolean reducedMotionEnabled = true;
    private boolean largeTextEnabled = true;
    private boolean highContrastEnabled;
    private String informationDensity = "표준";
    private boolean preventDuplicateOrders = true;
    private int maxSubscriptions = 160;
    private SonificationPreferences sonificationPreferences = SonificationPreferences.DEFAULT;
    private String pendingOrderPrice = "73500";
    private OrderDraft orderDraft;
    public DesktopApplication() {
        this(DesktopServices.createDefault());
    }

    DesktopApplication(DesktopServices services) {
        this.tradingUseCase = services.trading();
        this.marketApplication = services.market();
        this.stockAdapter = services.stocks();
        this.candleAdapter = services.candles();
        this.speechPort = services.speech();
        this.speechQueue = services.speechQueue();
        this.soundPort = services.sounds();
        this.sonificationPort = services.sonification();
        this.secretStore = services.secrets();
        this.stateRepository = services.stateRepository();
        this.accessibilityPreferencesRepository = services.accessibilityPreferences();
        this.sonificationPreferencesRepository = services.sonificationPreferences();
        this.connectionViewModel = new ConnectionViewModel(secretStore);
        this.stockSearchViewModel = new StockSearchViewModel(
                session, marketApplication, Platform::runLater);
        this.watchlistViewModel = new WatchlistViewModel(
                session, marketApplication, Platform::runLater);
        this.stockDetailViewModel = new StockDetailViewModel(
                session, marketApplication, Platform::runLater);
    }

    @Override public void start(Stage stage) {
        restoreLocalState();
        session.onChange(this::scheduleStateSave);
        root = new BorderPane();
        root.getStyleClass().add("app-root");
        if (largeTextEnabled) root.getStyleClass().add("large-text");
        if (highContrastEnabled) root.getStyleClass().add("high-contrast");
        root.setMinSize(0, 0);
        screenHost.setMinSize(0, 0);
        root.setLeft(createSidebar());
        root.setTop(createTopBar());
        root.setCenter(screenHost);
        root.setBottom(createStatusBar());
        status.setAccessibleText("앱 상태. " + status.getText());
        status.textProperty().addListener((obs, old, message) -> Platform.runLater(() -> {
            status.setAccessibleText("앱 상태. " + message);
            status.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
        }));
        rebuildAccessibleChart(session.selectedStock().symbol());
        configureScreens();
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

        var visualBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1280, visualBounds.getWidth() * 0.92);
        double initialHeight = Math.min(820, visualBounds.getHeight() * 0.90);
        Scene scene = new Scene(root, initialWidth, initialHeight);
        scene.getStylesheets().add(getClass().getResource("/styles/application.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.DASHBOARD));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.A, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.ACCOUNT));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.ALT_DOWN),
                () -> focusGlobalSearch());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.ALT_DOWN),
                () -> navigate(Screen.RADIO));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O, KeyCombination.ALT_DOWN),
                () -> openOrder(OrderSide.BUY));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN),
                this::navigateBack);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                () -> navigate(Screen.SETTINGS));
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) {
                cycleFocusRegion(event.isShiftDown());
                event.consume();
            }
        });

        navigate(Screen.DASHBOARD);
        stage.setTitle("OpenStock Access - 모의투자 UI");
        stage.setMinWidth(Math.min(1040, visualBounds.getWidth() * 0.82));
        stage.setMinHeight(Math.min(650, visualBounds.getHeight() * 0.82));
        stage.setMaxWidth(visualBounds.getWidth());
        stage.setMaxHeight(visualBounds.getHeight());
        stage.setX(visualBounds.getMinX() + (visualBounds.getWidth() - initialWidth) / 2);
        stage.setY(visualBounds.getMinY() + (visualBounds.getHeight() - initialHeight) / 2);
        stage.setScene(scene); stage.show();
    }

    private VBox createSidebar() {
        Label product = new Label("OpenStock\nAccess");
        product.getStyleClass().add("sidebar-title");
        Label mode = new Label("모의투자 · UI 데모");
        mode.getStyleClass().add("mode-badge");
        ComboBox<Screen> quickNavigation = new ComboBox<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(Screen.values()).filter(Screen::shownInSidebar).toList()));
        quickNavigation.setPromptText("화면 바로 이동");
        quickNavigation.setAccessibleText("화면 바로 이동");
        quickNavigation.setMaxWidth(Double.MAX_VALUE);
        quickNavigation.setConverter(new StringConverter<>() {
            @Override public String toString(Screen screen) {
                return screen == null ? "" : screen.navigationGroup().label() + " · " + screen.label();
            }
            @Override public Screen fromString(String value) { return null; }
        });
        quickNavigation.setOnAction(event -> {
            Screen selected = quickNavigation.getValue();
            if (selected != null) openNavigationScreen(selected);
        });
        VBox nav = new VBox(4);
        Screen.NavigationGroup currentGroup = null;
        for (Screen screen : Screen.values()) {
            if (!screen.shownInSidebar()) continue;
            if (screen.navigationGroup() != currentGroup) {
                currentGroup = screen.navigationGroup();
                Label group = new Label(currentGroup.label());
                group.getStyleClass().add("nav-group-label");
                group.setAccessibleText(currentGroup.label() + " 메뉴 그룹");
                nav.getChildren().add(group);
            }
            Button button = new Button(screen.label());
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAccessibleText(screen.label() + " 화면 열기");
            button.setOnAction(event -> openNavigationScreen(screen));
            navigationButtons.put(screen, button);
            nav.getChildren().add(button);
        }
        ScrollPane navScroll = new ScrollPane(nav);
        navScroll.getStyleClass().add("sidebar-scroll");
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(navScroll, Priority.ALWAYS);
        Label help = new Label("키보드\nF6 / Shift+F6 영역 이동\nAlt+← 뒤로 · Alt+D 홈 · Alt+S 검색\nAlt+O 주문 · Alt+A 계좌\nAlt+R 청각 차트");
        help.getStyleClass().add("keyboard-help");
        VBox sidebar = new VBox(14, product, mode, quickNavigation, navScroll, help);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(20));
        sidebar.setPrefWidth(largeTextEnabled ? 250 : 216);
        sidebar.setMinWidth(largeTextEnabled ? 230 : 200);
        sidebarRoot = sidebar;
        return sidebar;
    }

    private void openNavigationScreen(Screen screen) {
        if (screen == Screen.TRADING) openOrder(OrderSide.BUY);
        else navigate(screen);
    }

    private VBox createTopBar() {
        backButton.setDisable(true);
        backButton.setAccessibleText("이전 화면으로 돌아가기");
        backButton.setAccessibleHelp("Alt와 왼쪽 방향키로도 이전 화면으로 돌아갈 수 있습니다.");
        backButton.setOnAction(event -> navigateBack());
        currentLocation.getStyleClass().add("muted-text");
        currentLocation.setAccessibleText("현재 화면 홈");

        globalSearch.setPromptText("종목명 또는 종목코드 검색");
        globalSearch.setAccessibleText("국내와 미국 종목 통합 검색");
        globalSearch.setAccessibleHelp("검색어를 입력하고 Enter 키를 누르면 종목 상세 화면을 엽니다.");
        globalSearch.setPrefWidth(320);
        globalSearch.setMinWidth(180);
        globalSearch.setOnAction(event -> openSearchedStock());

        Button searchButton = new Button("검색");
        searchButton.setOnAction(event -> openSearchedStock());
        HBox search = new HBox(8, globalSearch, searchButton);
        search.setAlignment(Pos.CENTER_LEFT);
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(globalSearch, Priority.ALWAYS);

        Label market = new Label("데모 시세 · 고정 스냅샷");
        market.getStyleClass().addAll("status-chip", "mode-badge");
        Button connection = new Button("키움 API · 미연결");
        connection.getStyleClass().add("connection-button");
        connection.setOnAction(event -> navigate(Screen.CONNECTION));
        Button alerts = new Button("알림 3");
        alerts.setOnAction(event -> navigate(Screen.NOTIFICATIONS));
        Button account = new Button("계좌 ****-1204");
        account.setOnAction(event -> navigate(Screen.ACCOUNT));

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox context = new HBox(12, backButton, currentLocation, spacer, market, alerts, account, connection);
        context.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(10, context, search);
        top.getStyleClass().add("top-bar");
        top.setPadding(new Insets(12, 18, 12, 18));
        return top;
    }

    private void focusGlobalSearch() {
        globalSearch.requestFocus();
        globalSearch.selectAll();
    }

    private void cycleFocusRegion(boolean reverse) {
        Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        boolean inTop = isDescendantOf(focused, root.getTop());
        boolean inContent = isDescendantOf(focused, root.getCenter());
        if (reverse) {
            if (inTop) focusSidebar();
            else if (inContent) focusGlobalSearch();
            else screenController.focusContent();
        } else {
            if (inTop) screenController.focusContent();
            else if (inContent) focusSidebar();
            else focusGlobalSearch();
        }
    }

    private void focusSidebar() {
        Button first = navigationButtons.get(Screen.DASHBOARD);
        if (first != null) first.requestFocus();
    }

    private boolean isDescendantOf(Node node, Node ancestor) {
        if (!(ancestor instanceof Parent) || node == null) return false;
        for (Node candidate = node; candidate != null; candidate = candidate.getParent()) {
            if (candidate == ancestor) return true;
        }
        return false;
    }

    private void openSearchedStock() {
        if (globalSearch.getText() == null || globalSearch.getText().isBlank()) {
            navigate(Screen.SEARCH);
            return;
        }
        String query = globalSearch.getText().trim();
        status.setText(query + " 종목을 조회하고 있습니다.");
        stockSearchViewModel.filter(query, "전체").thenAccept(result -> {
            if (!result.applied()) return;
            if (!stockSearchViewModel.lastError().isBlank()) {
                screenController.invalidate(Screen.SEARCH);
                navigate(Screen.SEARCH);
                status.setText(stockSearchViewModel.lastError());
                return;
            }
            var selected = stockSearchViewModel.exactMatch(query)
                    .orElseGet(() -> result.count() == 1 ? stockSearchViewModel.items().get(0) : null);
            if (selected == null) {
                screenController.invalidate(Screen.SEARCH);
                navigate(Screen.SEARCH);
                status.setText(result.count() == 0 ? query + " 검색 결과가 없습니다."
                        : query + " 검색 결과 " + result.count() + "건에서 종목을 선택해주세요.");
                return;
            }
            stockSearchViewModel.select(selected);
            navigate(Screen.STOCK_DETAIL);
        });
    }

    private void openStockByQuery(String query) {
        selectStockByQuery(query).thenAccept(selected -> {
            if (selected) navigate(Screen.STOCK_DETAIL);
        });
    }

    private java.util.concurrent.CompletionStage<Boolean> selectStockByQuery(String query) {
        status.setText(query + " 종목을 조회하고 있습니다.");
        return stockSearchViewModel.findBestMatch(query).thenApply(selected -> {
            if (selected == null) {
                status.setText(query + " 종목 정보를 찾지 못했습니다.");
                return false;
            }
            stockSearchViewModel.select(selected);
            return true;
        });
    }

    private void openSelectedStock(TableView<ObservableList<String>> table, int nameColumn) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.size() <= nameColumn) {
            status.setText("열어볼 종목을 먼저 선택해주세요.");
            table.requestFocus();
            return;
        }
        openStockByQuery(selected.get(nameColumn));
    }

    private void navigateForSelectedStock(TableView<ObservableList<String>> table, int nameColumn, OrderSide side) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.size() <= nameColumn) {
            status.setText("주문할 종목을 먼저 선택해주세요."); table.requestFocus(); return;
        }
        selectStockByQuery(selected.get(nameColumn)).thenAccept(found -> {
            if (found) openOrder(side);
        });
    }

    private void navigate(Screen screen) {
        if (screen == Screen.STOCK_DETAIL) {
            status.setText(session.selectedStock().name() + " 상세와 차트를 조회하고 있습니다.");
            stockDetailViewModel.loadInitial().whenComplete((data, failure) -> {
                if (failure != null) {
                    status.setText("종목 상세를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                    play(SoundCue.ERROR);
                } else {
                    showScreen(screen);
                    status.setText(data.detail().name() + " 상세 화면을 열었습니다.");
                }
            });
            return;
        }
        if (screen == Screen.TRADING && !stockDetailViewModel.hasCurrentDetail()) {
            status.setText(session.selectedStock().name() + " 주문 기준가를 조회하고 있습니다.");
            stockDetailViewModel.loadDetail().whenComplete((detail, failure) -> {
                if (failure != null) {
                    status.setText("주문 기준가를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                    play(SoundCue.ERROR);
                } else showScreen(screen);
            });
            return;
        }
        showScreen(screen);
    }

    private void showScreen(Screen screen) {
        if (screen == Screen.WATCHLIST || screen == Screen.US_MARKET) watchlistViewModel.refresh();
        if (screen == Screen.RADIO
                && !accessibleChartController.stock().symbol().equals(session.selectedStock().symbol())) {
            rebuildAccessibleChart(session.selectedStock().symbol());
            screenController.invalidate(Screen.RADIO);
        }
        if (screen == Screen.STOCK_DETAIL || screen == Screen.TRADING) screenController.invalidate(screen);
        screenController.show(screen);
    }

    private void rebuildAccessibleChart(String symbol) {
        if (accessibleChartController != null) {
            sonificationPreferences = accessibleChartController.preferences();
            accessibleChartController.close();
        }
        accessibleChartController = new AccessibleChartController(
                symbol, stockAdapter, candleAdapter, sonificationPort, this::requestSpeech, status::setText);
        accessibleChartController.applyPreferences(sonificationPreferences);
        accessibleChartController.setPreferencesListener(preferences -> {
            sonificationPreferences = preferences;
            scheduleStateSave();
        });
        accessibleChartView = new AccessibleChartView(accessibleChartController);
    }

    private void navigateBack() {
        if (!screenController.goBack()) status.setText("이전 화면 기록이 없습니다.");
    }

    private void openOrder(OrderSide side) {
        if (!stockDetailViewModel.hasCurrentDetail()) {
            status.setText(session.selectedStock().name() + " 주문 기준가를 조회하고 있습니다.");
            stockDetailViewModel.loadDetail().whenComplete((detail, failure) -> {
                if (failure != null) {
                    status.setText("주문 기준가를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                    play(SoundCue.ERROR);
                } else prepareOrder(side, detail.currentPrice().stripTrailingZeros().toPlainString());
            });
            return;
        }
        prepareOrder(side, stockDetailViewModel.plainOrderPrice());
    }

    private void prepareOrder(OrderSide side, String price) {
        StockDetail detail = stockDetailViewModel.detail();
        Screen origin = screenController.currentScreen().orElse(Screen.DASHBOARD);
        orderDraft = new OrderDraft(detail.symbol(), detail.name(), side,
                OrderType.LIMIT, 1, price, origin);
        pendingOrderPrice = orderDraft.price();
        navigate(Screen.TRADING);
    }

    private void openOrderAtPrice(OrderSide side, String price) {
        if (!stockDetailViewModel.hasCurrentDetail()) {
            status.setText("선택 종목 정보를 다시 조회하고 있습니다.");
            stockDetailViewModel.loadDetail().whenComplete((detail, failure) -> {
                if (failure != null) status.setText("주문 기준가를 조회하지 못했습니다.");
                else prepareOrder(side, price);
            });
        } else prepareOrder(side, price);
    }

    private void configureScreens() {
        screenController = new DesktopScreenController(screenHost, navigationButtons, status::setText);
        backButton.disableProperty().unbind();
        backButton.disableProperty().bind(screenController.canGoBackProperty().not());
        screenController.currentScreenProperty().addListener((obs, old, screen) -> {
            if (screen == null) return;
            String location = switch (screen) {
                case STOCK_DETAIL -> "종목 상세 · " + session.selectedStock().name();
                case TRADING -> "주문 · " + session.selectedStock().name();
                default -> screen.label();
            };
            currentLocation.setText(location);
            currentLocation.setAccessibleText("현재 화면 " + location);
        });
        screenController.register(Screen.DASHBOARD, this::createDashboard);
        screenController.register(Screen.CONNECTION, this::createConnectionScreen);
        screenController.register(Screen.MARKET, this::createMarketScreen);
        screenController.registerPreservingState(Screen.SEARCH, this::createSearchScreen);
        screenController.register(Screen.STOCK_DETAIL, this::createStockScreen);
        screenController.registerPreservingState(Screen.WATCHLIST, this::createWatchlistScreen);
        screenController.register(Screen.SCANNER, this::createScannerScreen);
        screenController.register(Screen.CONDITION, this::createConditionScreen);
        screenController.register(Screen.SUPPLY, this::createSupplyScreen);
        screenController.register(Screen.TRADING, this::createTradingScreen);
        screenController.register(Screen.ACCOUNT, this::createAccountScreen);
        screenController.register(Screen.US_MARKET, this::createUsMarketScreen);
        screenController.registerPreservingState(Screen.NOTIFICATIONS, this::createNotificationsScreen);
        screenController.register(Screen.RADIO, () -> accessibleChartView.root());
        screenController.registerPreservingState(Screen.SETTINGS, this::createSettingsScreen);
    }

    private ScrollPane createDashboard() {
        Label title = heading("안녕하세요. 오늘의 투자 현황입니다");
        Label description = new Label("모의투자 데이터 · 2026년 8월 10일 기준");
        description.getStyleClass().add("muted-text");
        VBox intro = new VBox(4, title, description);
        Button order = primaryButton("주문하기", () -> openOrder(OrderSide.BUY));
        Button listen = new Button("화면 요약 듣기");
        listen.setOnAction(event -> requestSpeech(
                "총 자산 5천 2백 34만원, 오늘 평가손익은 143만원 이익입니다. 코스피와 코스닥 모두 상승 중입니다.",
                "dashboard-summary"));
        Region titleSpacer = new Region(); HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, intro, titleSpacer, listen, order); header.setAlignment(Pos.CENTER_LEFT);

        FlowPane assets = wrappingRow(14,
                summaryCard("총 자산", "52,340,000원", "전일 대비 +1.8%", "positive"),
                summaryCard("평가손익", "+1,430,000원", "수익률 +2.81%", "positive"),
                summaryCard("오늘 실현손익", "+230,000원", "체결 4건", "positive"),
                summaryCard("주문 가능 금액", "8,200,000원", "D+2 8,450,000원", "neutral"));

        FlowPane indices = wrappingRow(12,
                compactMarketCard("KOSPI", "3,245.12", "+1.23%", true),
                compactMarketCard("KOSDAQ", "912.32", "+0.83%", true),
                compactMarketCard("NASDAQ", "18,425.30", "+0.42%", true),
                compactMarketCard("USD/KRW", "1,348.20", "-0.31%", false));

        TableView<ObservableList<String>> holdings = textTable("홈 보유종목 요약",
                List.of(
                        row("삼성전자", "100주", "72,500원", "+6.30%"),
                        row("SK하이닉스", "35주", "184,500원", "+4.12%"),
                        row("NAVER", "20주", "205,000원", "-2.38%")
                ), "종목", "수량", "현재가", "수익률");
        holdings.setPrefHeight(210);
        holdings.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(holdings, 0); });
        holdings.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(holdings, 0); });

        TableView<ObservableList<String>> ranking = textTable("오늘 시장 거래대금 상위",
                List.of(
                        row("1", "삼성전자", "2.1조", "+2.12%"),
                        row("2", "SK하이닉스", "1.4조", "+1.42%"),
                        row("3", "현대차", "8,420억", "+0.64%"),
                        row("4", "NAVER", "6,850억", "-0.71%")
                ), "순위", "종목", "거래대금", "등락률");
        ranking.setPrefHeight(210);
        ranking.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(ranking, 1); });
        ranking.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(ranking, 1); });

        VBox left = card("보유종목", holdings, linkButton("계좌 전체 보기", Screen.ACCOUNT));
        VBox right = card("오늘 시장", ranking, linkButton("랭킹 전체 보기", Screen.SCANNER));
        SplitPane center = new SplitPane(left, right); center.setDividerPositions(0.5);
        left.setMinWidth(0); right.setMinWidth(0);

        ListView<String> activity = new ListView<>(FXCollections.observableArrayList(
                "14:32 · 삼성전자 매수 10주 중 5주가 체결되었습니다.",
                "14:28 · 키움 실시간 시세 연결이 복구되었습니다.",
                "13:55 · SK하이닉스 목표 가격 184,000원에 도달했습니다."));
        activity.setAccessibleText("최근 주문과 알림"); activity.setPrefHeight(145);
        VBox activityCard = card("최근 주문 · 알림", activity);

        VBox body = new VBox(20, header, assets, indices, center, activityCard);
        return scrollPage("홈 대시보드", body);
    }

    private ScrollPane createConnectionScreen() {
        return new ConnectionScreenView(connectionViewModel, status::setText).create();
    }

    private ScrollPane createAccountScreen() {
        Account snapshot = tradingUseCase.account();
        Label title = heading("계좌");
        ComboBox<String> account = new ComboBox<>(FXCollections.observableArrayList("모의계좌 ****-1204", "미국주식 모의계좌 ****-7781"));
        account.setValue("모의계좌 ****-1204"); account.setAccessibleText("조회할 계좌 선택");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, account); header.setAlignment(Pos.CENTER_LEFT);

        FlowPane metrics = wrappingRow(14,
                summaryCard("총 평가자산", "52,300,000원", "국내주식 + 예수금", "neutral"),
                summaryCard("평가손익", "+3,100,000원", "수익률 +7.52%", "positive"),
                summaryCard("총 매입금액", "41,200,000원", "보유 6종목", "neutral"),
                summaryCard("예수금", "8,000,000원", "주문 가능 7,820,000원", "neutral"));

        TableView<ObservableList<String>> holdings = textTable("보유종목 표",
                List.of(
                        row("삼성전자", "100", "68,200원", "72,500원", "7,250,000원", "+430,000원", "+6.30%"),
                        row("SK하이닉스", "35", "177,200원", "184,500원", "6,457,500원", "+255,500원", "+4.12%"),
                        row("NAVER", "20", "210,000원", "205,000원", "4,100,000원", "-100,000원", "-2.38%")
                ), "종목", "수량", "평균단가", "현재가", "평가금액", "손익", "수익률");
        holdings.setPrefHeight(300);
        holdings.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(holdings, 0); });
        holdings.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(holdings, 0); });
        Button holdingDetail = new Button("선택 종목 상세"); holdingDetail.setOnAction(event -> openSelectedStock(holdings, 0));
        Button holdingBuy = primaryButton("선택 종목 매수", () -> navigateForSelectedStock(holdings, 0, OrderSide.BUY));
        Button holdingSell = new Button("선택 종목 매도"); holdingSell.setOnAction(event -> navigateForSelectedStock(holdings, 0, OrderSide.SELL));
        VBox holdingsPanel = new VBox(10, holdings, wrappingRow(8, holdingDetail, holdingBuy, holdingSell));
        holdingsPanel.setPadding(new Insets(10));

        VBox cash = new VBox(12,
                informationRow("예수금", "8,000,000원"),
                informationRow("D+1 예수금", "8,130,000원"),
                informationRow("D+2 예수금", "8,450,000원"),
                informationRow("출금 가능 금액", "7,650,000원"),
                informationRow("주문 가능 금액", "7,820,000원"));
        cash.setPadding(new Insets(20));

        TableView<ObservableList<String>> open = orderStatusTable(true);
        TableView<ObservableList<String>> fills = orderStatusTable(false);
        TableView<ObservableList<String>> history = textTable("주문내역",
                List.of(row("08/10 14:30", "삼성전자", "매수", "72,400원", "10", "부분체결"),
                        row("08/10 13:12", "NAVER", "매도", "시장가", "3", "체결")),
                "시간", "종목", "구분", "주문가", "수량", "상태");
        VBox profit = new VBox(16,
                new Label("모의투자 계좌의 기간별 누적 수익률입니다."),
                progressMetric("1개월 누적 수익률", 0.68, "+6.80%"),
                progressMetric("3개월 누적 수익률", 0.42, "+4.20%"),
                progressMetric("1년 누적 수익률", 0.91, "+9.10%"));
        profit.setPadding(new Insets(20));
        TableView<JournalEntry> journal = typedTable("매매일지", session.journalEntries(),
                textColumn("날짜", JournalEntry::date),
                textColumn("종목", JournalEntry::securityName),
                textColumn("매수금액", JournalEntry::buyAmount),
                textColumn("매도금액", JournalEntry::sellAmount),
                textColumn("손익", JournalEntry::profitLoss),
                textColumn("전략·메모", JournalEntry::memo),
                textColumn("태그", JournalEntry::tags));
        Button addJournal = new Button("일지 작성"); addJournal.setOnAction(event -> showJournalDialog(null));
        Button editJournal = new Button("선택 수정"); editJournal.setOnAction(event -> {
            JournalEntry selected = journal.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("수정할 매매일지를 선택해주세요.");
                journal.requestFocus();
                return;
            }
            showJournalDialog(selected);
        });
        Button deleteJournal = new Button("선택 삭제");
        deleteJournal.setOnAction(event -> deleteSelectedJournal(journal));
        Button attach = new Button("차트 화면 첨부"); attach.setOnAction(event -> status.setText("현재 차트 화면을 매매일지 첨부 대상으로 선택했습니다."));
        VBox journalPanel = new VBox(10, journal, wrappingRow(8, addJournal, editJournal, deleteJournal, attach));
        journalPanel.setPadding(new Insets(10));

        TabPane tabs = new TabPane(
                tab("보유종목", holdingsPanel), tab("예수금", cash), tab("미체결", open),
                tab("체결", fills), tab("주문내역", history), tab("손익분석", profit), tab("매매일지", journalPanel));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(390);
        VBox body = new VBox(20, header, metrics, tabs);
        return scrollPage("계좌 대시보드", body);
    }

    private ScrollPane createTradingScreen() {
        StockDetail selectedDetail = stockDetailViewModel.detail();
        Label title = heading("주문");
        Label notice = new Label("모의투자 UI입니다. 확인을 눌러도 실제 키움 주문은 전송되지 않습니다.");
        notice.getStyleClass().add("safety-note"); notice.setWrapText(true);
        VBox quote = new VBox(8,
                sectionHeading(selectedDetail.name() + " · " + selectedDetail.symbol()),
                styledLabel(stockDetailViewModel.formatPrice(selectedDetail.currentPrice()), "stock-price"),
                new Label("전일 대비 " + signedChangeRate(selectedDetail)),
                informationRow("매도 1호가", stockDetailViewModel.formatPrice(selectedDetail.currentPrice().add(selectedDetail.currentPrice().multiply(new BigDecimal("0.001")))) + " · 18,001주"),
                informationRow("매수 1호가", stockDetailViewModel.formatPrice(selectedDetail.currentPrice().subtract(selectedDetail.currentPrice().multiply(new BigDecimal("0.001")))) + " · 15,321주"));
        quote.getStyleClass().add("panel-card"); quote.setPadding(new Insets(20)); quote.setPrefWidth(340);

        VBox orderForm = createOrderForm();
        SplitPane orderArea = new SplitPane(quote, orderForm); orderArea.setDividerPositions(0.38);
        quote.setMinWidth(0); orderForm.setMinWidth(0);

        TableView<ObservableList<String>> openOrders = orderStatusTable(true);
        TableView<ObservableList<String>> fills = orderStatusTable(false);
        Button amend = new Button("선택 주문 정정"); amend.setOnAction(event -> showAmendOrderDialog(openOrders));
        Button cancel = new Button("선택 주문 취소"); cancel.setOnAction(event -> cancelSelectedOrder(openOrders));
        Button cancelAll = new Button("미체결 전량 취소"); cancelAll.setOnAction(event -> cancelAllOrders(openOrders));
        FlowPane orderActions = wrappingRow(8, amend, cancel, cancelAll);
        VBox openContent = new VBox(10, stateBanner("재연결 후 주문 상태를 확인했습니다.", "success"), openOrders, orderActions);
        VBox.setVgrow(openOrders, Priority.ALWAYS);
        TabPane statusTabs = new TabPane(tab("미체결", openContent), tab("체결", fills));
        statusTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); statusTabs.setPrefHeight(260);
        VBox body = new VBox(18, title, notice, orderArea, sectionHeading("주문 상태"), statusTabs);
        return scrollPage("주문", body);
    }

    private ScrollPane createStockScreen() {
        StockDetail detail = stockDetailViewModel.detail();
        var selection = stockDetailViewModel.selection();
        pendingOrderPrice = stockDetailViewModel.plainOrderPrice();
        Label title = heading(detail.name());
        Label symbol = new Label(detail.symbol() + " · " + selection.exchange()); symbol.getStyleClass().add("mode-badge");
        Button favorite = new Button("관심종목 추가");
        favorite.setOnAction(event -> {
            status.setText(detail.name() + " 종목을 확인하고 있습니다.");
            stockSearchViewModel.findBestMatch(detail.symbol()).thenAccept(item -> {
                if (item == null) status.setText(detail.name() + " 종목 정보를 찾지 못했습니다.");
                else status.setText(stockSearchViewModel.addToWatchlist(item)
                        ? detail.name() + "을 관심종목에 추가했습니다."
                        : detail.name() + "은 이미 관심종목에 있습니다.");
            });
        });
        Button buy = primaryButton("매수", () -> openOrder(OrderSide.BUY));
        Button sell = new Button("매도"); sell.getStyleClass().add("sell-button"); sell.setOnAction(event -> openOrder(OrderSide.SELL));
        Region titleSpacer = new Region(); HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(12, title, symbol, titleSpacer, favorite, sell, buy); titleRow.setAlignment(Pos.CENTER_LEFT);
        String direction = detail.direction() == PriceDirection.UP ? "상승" : detail.direction() == PriceDirection.DOWN ? "하락" : "보합";
        Label price = new Label(stockDetailViewModel.formatPrice(detail.currentPrice()) + " · " + direction + " "
                + stockDetailViewModel.formatPrice(detail.changeAmount().abs()) + " · " + detail.changeRate().abs() + "%");
        price.getStyleClass().add("stock-price");
        Button listen = new Button("최신 정보 듣기");
        listen.setOnAction(event -> requestSpeech(detail.name() + " 현재가 "
                + stockDetailViewModel.formatPrice(detail.currentPrice())
                + ", 전일 대비 " + direction + " " + detail.changeRate().abs() + "퍼센트입니다.",
                "stock-detail-" + detail.symbol()));

        FlowPane metrics = wrappingRow(12,
                miniMetric("시가", stockDetailViewModel.formatPrice(detail.open())), miniMetric("고가", stockDetailViewModel.formatPrice(detail.high())),
                miniMetric("저가", stockDetailViewModel.formatPrice(detail.low())), miniMetric("거래량", String.format("%,d", detail.volume())),
                miniMetric("시가총액", "432.8조"), miniMetric("외국인", "54.1%"));

        FlowPane periods = wrappingRow(8);
        ToggleGroup periodGroup = new ToggleGroup();
        Map<ToggleButton, StockDetailViewModel.ChartRange> periodButtons = new java.util.LinkedHashMap<>();
        for (StockDetailViewModel.ChartRange range : StockDetailViewModel.ChartRange.values()) {
            ToggleButton button = new ToggleButton(range.label()); button.setToggleGroup(periodGroup);
            if (range == StockDetailViewModel.ChartRange.DAY) button.setSelected(true);
            periodButtons.put(button, range); periods.getChildren().add(button);
        }

        List<PricePoint> chartPoints = stockDetailViewModel.history(StockDetailViewModel.ChartRange.DAY);
        CandlestickChartView candleChart = new CandlestickChartView(chartPoints);
        CheckBox movingAverage = new CheckBox("이동평균"); movingAverage.setSelected(true);
        CheckBox bollinger = new CheckBox("Bollinger Band");
        CheckBox rsi = new CheckBox("RSI");
        CheckBox macd = new CheckBox("MACD");
        movingAverage.selectedProperty().addListener((obs, old, value) -> candleChart.setShowMovingAverages(value));
        bollinger.selectedProperty().addListener((obs, old, value) -> candleChart.setShowBollinger(value));
        rsi.selectedProperty().addListener((obs, old, value) -> candleChart.setShowRsi(value));
        macd.selectedProperty().addListener((obs, old, value) -> candleChart.setShowMacd(value));
        FlowPane indicators = wrappingRow(8, movingAverage, bollinger, rsi, macd);

        TableView<PricePoint> history = new TableView<>(FXCollections.observableArrayList(chartPoints));
        history.setAccessibleText(detail.name() + " 최근 가격 흐름 표");
        history.setAccessibleHelp("차트와 동일한 날짜별 시가, 고가, 저가, 종가와 거래량입니다.");
        history.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        history.getColumns().add(priceColumn("날짜", point -> point.date().toString()));
        history.getColumns().add(priceColumn("시가", point -> stockDetailViewModel.formatPrice(point.open())));
        history.getColumns().add(priceColumn("고가", point -> stockDetailViewModel.formatPrice(point.high())));
        history.getColumns().add(priceColumn("저가", point -> stockDetailViewModel.formatPrice(point.low())));
        history.getColumns().add(priceColumn("종가", point -> stockDetailViewModel.formatPrice(point.close())));
        history.getColumns().add(priceColumn("거래량", point -> Long.toString(point.volume())));
        history.setPrefHeight(350);
        periodButtons.forEach((button, range) -> button.setOnAction(event -> {
            button.setDisable(true);
            status.setText(detail.name() + " " + range.label() + " 차트를 조회하고 있습니다.");
            stockDetailViewModel.loadHistory(range).whenComplete((updated, failure) -> {
                button.setDisable(false);
                if (failure != null || updated.isEmpty()) {
                    status.setText(detail.name() + " 차트를 조회하지 못했습니다.");
                    play(SoundCue.ERROR);
                } else {
                    candleChart.setPoints(updated);
                    history.getItems().setAll(updated);
                    status.setText(detail.name() + " " + range.label() + " 차트로 변경했습니다.");
                }
            });
        }));
        Button soundChart = new Button("이 차트를 소리로 탐색");
        soundChart.setOnAction(event -> navigate(Screen.RADIO));
        TabPane chartRepresentations = new TabPane(tab("그래프", candleChart), tab("접근 가능한 표", history));
        chartRepresentations.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); chartRepresentations.setPrefHeight(430);
        VBox chart = new VBox(12, periods, indicators, chartRepresentations, soundChart); chart.setPadding(new Insets(10));

        BigDecimal quoteStep = selection.overseas() ? new BigDecimal("0.10") : new BigDecimal("100");
        TableView<ObservableList<String>> orderBook = textTable(detail.name() + " 10호가",
                List.of(
                        row("매도", stockDetailViewModel.formatPrice(detail.currentPrice().add(quoteStep.multiply(new BigDecimal("4")))), "12,231", "+1,120"),
                        row("매도", stockDetailViewModel.formatPrice(detail.currentPrice().add(quoteStep.multiply(new BigDecimal("3")))), "9,231", "-410"),
                        row("매도", stockDetailViewModel.formatPrice(detail.currentPrice().add(quoteStep.multiply(new BigDecimal("2")))), "14,210", "+820"),
                        row("매도", stockDetailViewModel.formatPrice(detail.currentPrice().add(quoteStep)), "21,910", "+2,103"),
                        row("매수", stockDetailViewModel.formatPrice(detail.currentPrice().subtract(quoteStep)), "15,321", "+420"),
                        row("매수", stockDetailViewModel.formatPrice(detail.currentPrice().subtract(quoteStep.multiply(new BigDecimal("2")))), "32,110", "+3,010"),
                        row("매수", stockDetailViewModel.formatPrice(detail.currentPrice().subtract(quoteStep.multiply(new BigDecimal("3")))), "11,034", "-950"),
                        row("매수", stockDetailViewModel.formatPrice(detail.currentPrice().subtract(quoteStep.multiply(new BigDecimal("4")))), "25,000", "+1,340")
                ), "구분", "호가", "잔량", "잔량 변화");
        orderBook.setPrefHeight(360);
        Runnable useSelectedQuote = () -> {
            ObservableList<String> selected = orderBook.getSelectionModel().getSelectedItem();
            if (selected == null || selected.size() < 2) {
                status.setText("주문에 반영할 호가를 선택해주세요.");
                orderBook.requestFocus();
                return;
            }
            String selectedPrice = selected.get(1).replaceAll("[^0-9.]", "");
            status.setText("선택한 호가 " + selected.get(1) + "을 주문 가격에 입력했습니다.");
            OrderSide selectedSide = "매도".equals(selected.get(0)) ? OrderSide.BUY : OrderSide.SELL;
            openOrderAtPrice(selectedSide, selectedPrice);
        };
        orderBook.setOnMouseClicked(event -> { if (event.getClickCount() == 2) useSelectedQuote.run(); });
        orderBook.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) useSelectedQuote.run(); });

        TableView<ObservableList<String>> trades = textTable(detail.name() + " 실시간 체결",
                List.of(row("14:32:01", stockDetailViewModel.formatPrice(detail.currentPrice()), signedChangeRate(detail), "321", "108.3"),
                        row("14:31:59", stockDetailViewModel.formatPrice(detail.currentPrice().subtract(quoteStep)), signedChangeRate(detail), "122", "107.9"),
                        row("14:31:57", stockDetailViewModel.formatPrice(detail.currentPrice()), signedChangeRate(detail), "84", "108.1")),
                "시간", "가격", "등락률", "체결량", "체결강도");
        trades.setPrefHeight(330);

        TableView<ObservableList<String>> supply = textTable(detail.name() + " 투자자 수급",
                List.of(row("08/10", "+320,140", "-120,300", "+42,100"),
                        row("08/09", "+240,050", "+30,120", "-18,920"),
                        row("08/08", "-84,200", "+54,880", "+5,410")),
                "날짜", "외국인", "기관", "프로그램");
        supply.setPrefHeight(330);

        GridPane info = new GridPane(); info.setHgap(24); info.setVgap(14); info.setPadding(new Insets(20));
        addInfo(info, 0, 0, "PER", "18.42배"); addInfo(info, 1, 0, "PBR", "1.54배");
        addInfo(info, 0, 1, "EPS", "3,935원"); addInfo(info, 1, 1, "52주 최고", "86,000원");
        addInfo(info, 0, 2, "신용비율", "0.14%"); addInfo(info, 1, 2, "VI 상태", "정상");

        TabPane tabs = new TabPane(tab("차트", chart), tab("호가", orderBook), tab("체결", trades),
                tab("수급", supply), tab("거래원", createBrokerAnalysisPanel()),
                tab("프로그램", createStockProgramPanel()), tab("기업정보", info));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(440);
        VBox body = new VBox(16, titleRow, wrappingRow(12, price, listen), metrics, tabs);
        return scrollPage("종목 상세 " + detail.name(), body);
    }

    private ScrollPane createMarketScreen() {
        Label title = heading("국내 시장");
        FlowPane marketSummary = wrappingRow(14,
                summaryCard("KOSPI", "3,245.12", "+39.45 · +1.23%", "positive"),
                summaryCard("KOSDAQ", "912.32", "+7.51 · +0.83%", "positive"),
                summaryCard("상승 / 보합 / 하락", "1,203 / 103 / 832", "상승 종목 우세", "neutral"));

        TableView<ObservableList<String>> movers = textTable("국내시장 주요 종목",
                List.of(row("1", "삼성전자", "72,500원", "+2.12%", "18,320,122", "2.1조"),
                        row("2", "SK하이닉스", "184,500원", "+1.42%", "5,821,330", "1.4조"),
                        row("3", "한미반도체", "132,200원", "+6.82%", "3,129,443", "4,120억"),
                        row("4", "NAVER", "205,000원", "-0.71%", "1,230,922", "2,540억")),
                "순위", "종목", "현재가", "등락률", "거래량", "거래대금");
        movers.setPrefHeight(350); movers.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(movers, 1); });
        movers.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(movers, 1); });
        Button openMarketStock = new Button("선택 종목 열기");
        openMarketStock.setOnAction(event -> openSelectedStock(movers, 1));

        ToggleGroup group = new ToggleGroup(); FlowPane filters = wrappingRow(8);
        for (String value : List.of("거래량", "거래대금", "상승률", "하락률", "외국인", "기관", "프로그램")) {
            ToggleButton button = new ToggleButton(value); button.setToggleGroup(group);
            button.setOnAction(event -> sortMarketRows(movers, value));
            if (value.equals("거래대금")) button.setSelected(true); filters.getChildren().add(button);
        }
        sortMarketRows(movers, "거래대금");
        VBox domestic = new VBox(16, marketSummary, filters, movers, openMarketStock); domestic.setPadding(new Insets(12));
        TableView<ObservableList<String>> sectors = textTable("업종 지수",
                List.of(row("반도체", "4,820.31", "+3.40%", "54", "12"),
                        row("자동차", "2,145.82", "+1.82%", "31", "8"),
                        row("AI 소프트웨어", "1,884.20", "+1.35%", "26", "14"),
                        row("바이오", "3,101.44", "-0.62%", "19", "42")),
                "업종", "지수", "등락률", "상승", "하락");
        TableView<ObservableList<String>> themes = textTable("테마 목록",
                List.of(row("반도체", "+3.40%", "한미반도체", "+6.82%"),
                        row("AI", "+2.70%", "NAVER", "+1.12%"),
                        row("2차전지", "+1.20%", "에코프로", "+5.21%"),
                        row("바이오", "-0.62%", "셀트리온", "-1.10%")),
                "테마", "등락률", "대표 종목", "대표 등락률");
        TableView<ObservableList<String>> etf = textTable("ETF 목록",
                List.of(row("069500", "KODEX 200", "36,120원", "+1.02%", "0.08%", "6.4조"),
                        row("133690", "TIGER 미국나스닥100", "126,450원", "+0.45%", "0.07%", "3.1조"),
                        row("305720", "KODEX 2차전지", "12,830원", "+1.92%", "0.45%", "1.2조")),
                "코드", "ETF", "현재가", "등락률", "보수", "순자산");
        etf.setOnMouseClicked(event -> { if (event.getClickCount() == 2) showProductDetail("ETF", selectedName(etf, 1, "KODEX 200")); });
        etf.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) showProductDetail("ETF", selectedName(etf, 1, "KODEX 200")); });
        TableView<ObservableList<String>> elw = textTable("ELW 목록",
                List.of(row("58K123", "삼성전자 콜", "125원", "+8.70%", "86일", "0.42"),
                        row("57K882", "KOSPI200 풋", "210원", "-4.20%", "42일", "-0.36"),
                        row("58K220", "SK하이닉스 콜", "95원", "+12.30%", "71일", "0.51")),
                "코드", "종목", "현재가", "등락률", "잔존일", "델타");
        elw.setOnMouseClicked(event -> { if (event.getClickCount() == 2) showProductDetail("ELW", selectedName(elw, 1, "삼성전자 콜")); });
        elw.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) showProductDetail("ELW", selectedName(elw, 1, "삼성전자 콜")); });
        VBox gold = new VBox(16,
                wrappingRow(14, summaryCard("금 99.99 1g", "128,450원", "+0.74%", "positive"),
                        summaryCard("매수 1호가", "128,440원", "잔량 1,820g", "neutral"),
                        summaryCard("매도 1호가", "128,460원", "잔량 1,240g", "neutral")),
                textTable("금현물 호가와 체결",
                        List.of(row("14:31:58", "128,450원", "10g", "매수 체결"),
                                row("14:31:42", "128,440원", "25g", "매도 체결")),
                        "시간", "가격", "수량", "구분"),
                new HBox(10, primaryButton("금현물 매수", () -> showCommodityOrderConfirmation("금현물", "매수")),
                        primaryButton("금현물 매도", () -> showCommodityOrderConfirmation("금현물", "매도"))));
        gold.setPadding(new Insets(12));
        TabPane marketTabs = new TabPane(tab("국내시장", domestic), tab("업종", sectors), tab("테마", themes),
                tab("ETF", etf), tab("ELW", elw), tab("금현물", gold), tab("신용거래", createCreditTradingPanel()));
        marketTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); marketTabs.setPrefHeight(590);
        VBox body = new VBox(20, title, marketTabs);
        return scrollPage("국내 시장", body);
    }

    private ScrollPane createSearchScreen() {
        return new SearchScreenView(stockSearchViewModel, this::navigate, status::setText).create();
    }

    private ScrollPane createWatchlistScreen() {
        return new WatchlistScreenView(watchlistViewModel, this::openWatchlistStock,
                () -> startWatchlistSearch("전체"), status::setText).create();
    }

    private void openWatchlistStock(WatchlistItem item) {
        session.selectStock(item.toSelection());
        navigate(Screen.STOCK_DETAIL);
    }

    private void startWatchlistSearch(String market) {
        stockSearchViewModel.prepare("", market);
        screenController.invalidate(Screen.SEARCH);
        navigate(Screen.SEARCH);
        status.setText("관심종목에 추가할 종목을 검색해주세요.");
    }

    private ScrollPane createScannerScreen() {
        return new ScannerScreenView(scannerViewModel, status::setText, this::openStockByQuery).create();
    }

    private ScrollPane createConditionScreen() {
        Label title = heading("조건검색");
        ComboBox<String> condition = new ComboBox<>(FXCollections.observableArrayList(
                "거래량 돌파", "외국인 연속 순매수", "신고가 돌파", "단기 급등"));
        condition.setValue("거래량 돌파"); condition.setPrefWidth(280);
        CheckBox realtime = new CheckBox("실시간 편입·이탈 감시"); realtime.setSelected(true);
        ObservableList<ObservableList<String>> resultRows = FXCollections.observableArrayList();
        TableView<ObservableList<String>> results = textTable("조건검색 결과", resultRows,
                "시간", "상태", "종목", "현재가", "등락률", "조건 값");
        Label limit = new Label(); limit.getStyleClass().add("muted-text");
        Runnable search = () -> {
            List<String[]> selectedRows = switch (condition.getValue()) {
                case "외국인 연속 순매수" -> List.of(
                        row("14:30:10", "편입", "삼성전자", "72,500원", "+2.12%", "5일 연속 순매수"),
                        row("14:11:42", "편입", "현대차", "281,000원", "+0.64%", "3일 연속 순매수"));
                case "신고가 돌파" -> List.of(
                        row("14:28:03", "편입", "한미반도체", "132,200원", "+6.82%", "52주 신고가"),
                        row("13:52:17", "편입", "SK하이닉스", "184,500원", "+1.42%", "신고가 +0.4%"));
                case "단기 급등" -> List.of(
                        row("14:31:20", "편입", "한미반도체", "132,200원", "+6.82%", "10분 +4.1%"),
                        row("14:20:03", "이탈", "에코프로", "98,200원", "-1.20%", "상승폭 축소"));
                default -> List.of(
                        row("14:31:20", "편입", "삼성전자", "72,500원", "+2.12%", "거래량 185%"),
                        row("14:28:03", "편입", "한미반도체", "132,200원", "+6.82%", "거래량 312%"),
                        row("14:12:44", "이탈", "NAVER", "205,000원", "-0.71%", "거래량 82%"));
            };
            resultRows.setAll(selectedRows.stream().map(values -> FXCollections.observableArrayList(values)).toList());
            limit.setText("실시간 조건검색 " + (realtime.isSelected() ? "1" : "0") + " / 10개 사용 · 결과 " + resultRows.size() + " / 100종목");
            status.setText(condition.getValue() + " 조건에서 " + resultRows.size() + "개 종목을 찾았습니다.");
        };
        Button run = primaryButton("조건 검색", search);
        realtime.selectedProperty().addListener((obs, old, value) -> search.run());
        FlowPane controls = wrappingRow(12, labeledControl("키움 조건식", condition), realtime, run); controls.setAlignment(Pos.BOTTOM_LEFT);
        results.setPrefHeight(360);
        results.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(results, 2); });
        results.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(results, 2); });
        Button openConditionStock = new Button("선택 종목 열기");
        openConditionStock.setOnAction(event -> openSelectedStock(results, 2));
        search.run();
        return scrollPage("조건검색", new VBox(20, title, controls, limit, results, openConditionStock));
    }

    private ScrollPane createSupplyScreen() {
        Label title = heading("수급 분석");
        FlowPane summary = wrappingRow(14,
                summaryCard("외국인 순매수", "+2,430억원", "삼성전자 · 현대차 집중", "positive"),
                summaryCard("기관 순매수", "+840억원", "반도체 · 금융", "positive"),
                summaryCard("프로그램", "+960억원", "차익 +120 · 비차익 +840", "positive"));
        TableView<ObservableList<String>> table = textTable("투자자별 순매수 상위",
                List.of(row("삼성전자", "+320,140", "-120,300", "+42,100", "2.1조"),
                        row("현대차", "+104,320", "+88,100", "+12,840", "8,420억"),
                        row("SK하이닉스", "+82,100", "+54,200", "+18,210", "1.4조"),
                        row("NAVER", "-38,920", "+30,120", "-4,820", "2,540억")),
                "종목", "외국인", "기관", "프로그램", "거래대금");
        table.setPrefHeight(350);
        VBox program = new VBox(16,
                wrappingRow(14, summaryCard("차익", "+120억원", "전일 대비 +42억", "positive"),
                        summaryCard("비차익", "+840억원", "전일 대비 +180억", "positive"),
                        summaryCard("전체", "+960억원", "순매수 우위", "positive")),
                textTable("프로그램매매 시간대별 추이",
                        List.of(row("14:30", "+120억", "+840억", "+960억"),
                                row("14:00", "+84억", "+721억", "+805억"),
                                row("13:30", "+35억", "+588억", "+623억")),
                        "시간", "차익", "비차익", "전체"));
        program.setPadding(new Insets(12));
        VBox shortSelling = new VBox(16,
                wrappingRow(14, summaryCard("시장 공매도 거래대금", "8,420억원", "전체의 3.8%", "neutral"),
                        summaryCard("공매도 비중 증가", "42종목", "전일 대비 +8", "negative")),
                textTable("공매도 비중 상위",
                        List.of(row("셀트리온", "184,200원", "12.8%", "82,410", "+1.2%p"),
                                row("LG에너지솔루션", "352,000원", "9.4%", "31,202", "+0.8%p"),
                                row("NAVER", "205,000원", "7.2%", "54,129", "-0.3%p")),
                        "종목", "현재가", "공매도 비중", "공매도량", "전일 대비"));
        shortSelling.setPadding(new Insets(12));
        VBox lending = new VBox(16,
                wrappingRow(14, summaryCard("대차 잔고", "71.4조원", "전일 대비 +0.4조", "neutral"),
                        summaryCard("대차 증가 상위", "삼성전자", "+1,240,000주", "negative")),
                textTable("대차거래 증가 상위",
                        List.of(row("삼성전자", "1,240,000", "48,210,000", "+2.64%"),
                                row("SK하이닉스", "420,000", "12,840,000", "+3.38%"),
                                row("에코프로", "188,200", "4,221,000", "+4.67%")),
                        "종목", "증가량", "잔고", "증가율"));
        lending.setPadding(new Insets(12));
        TabPane tabs = new TabPane(tab("외국인 · 기관", table),
                tab("프로그램매매", program), tab("공매도", shortSelling), tab("대차거래", lending),
                tab("거래원", createBrokerAnalysisPanel()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(430);
        return scrollPage("수급 분석", new VBox(20, title, summary, tabs));
    }

    private ScrollPane createUsMarketScreen() {
        Label title = heading("미국주식");
        Label delayed = new Label("미국 시세 · UI 데모 데이터"); delayed.getStyleClass().add("mode-badge");
        HBox titleRow = new HBox(12, title, delayed); titleRow.setAlignment(Pos.CENTER_LEFT);
        TabPane tabs = new TabPane(
                tab("시장", createUsHomePanel()), tab("종목", createUsStockPanel()),
                tab("스캐너", createUsScannerPanel()), tab("조건검색", createUsConditionPanel()),
                tab("관심종목", createUsWatchlistPanel()),
                tab("계좌", createUsAccountPanel()), tab("주문", createUsOrderPanel()),
                tab("리서치", createUsResearchPanel()), tab("환전", createFxPanel()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(620);
        return scrollPage("미국주식", new VBox(20, titleRow, tabs));
    }

    private VBox createUsHomePanel() {
        FlowPane indices = wrappingRow(14,
                summaryCard("NASDAQ", "18,425.30", "+0.42%", "positive"),
                summaryCard("S&P 500", "5,582.10", "+0.31%", "positive"),
                summaryCard("DOW", "40,125.22", "-0.12%", "negative"),
                summaryCard("USD/KRW", "1,348.20", "-0.31%", "negative"));
        TableView<ObservableList<String>> ranking = textTable("미국주식 랭킹",
                List.of(row("NVDA", "NVIDIA", "$142.65", "+2.34%", "42.3M", "$3.5T"),
                        row("AAPL", "Apple", "$228.40", "+0.83%", "31.8M", "$3.4T"),
                        row("MSFT", "Microsoft", "$447.20", "+0.45%", "18.1M", "$3.3T"),
                        row("TSLA", "Tesla", "$216.10", "-1.28%", "51.2M", "$690B")),
                "티커", "종목", "현재가", "등락률", "거래량", "시가총액");
        ranking.setPrefHeight(330);
        Button fx = new Button("환전 화면 열기"); fx.setOnAction(event -> showFxDialog());
        Button usOrder = primaryButton("미국주식 주문", () -> showUsOrderConfirmation("NVDA", "매수", 1, "142.50"));
        HBox actions = new HBox(10, fx, usOrder);
        VBox panel = new VBox(18, indices, ranking, actions); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createUsStockPanel() {
        Label name = sectionHeading("NVIDIA · NVDA · NASDAQ");
        Label price = styledLabel("$142.65 · +$3.26 · +2.34%", "stock-price");
        FlowPane metrics = wrappingRow(12, miniMetric("시가", "$140.10"), miniMetric("고가", "$143.84"),
                miniMetric("저가", "$139.72"), miniMetric("거래량", "42.3M"), miniMetric("시가총액", "$3.5T"));
        TableView<ObservableList<String>> orderBook = textTable("NVIDIA 10호가",
                List.of(row("매도", "$142.72", "18,420"), row("매도", "$142.70", "21,830"),
                        row("매수", "$142.64", "14,221"), row("매수", "$142.62", "32,180")),
                "구분", "가격", "잔량");
        TableView<ObservableList<String>> trades = textTable("NVIDIA 체결",
                List.of(row("09:32:01", "$142.65", "1,240", "매수"), row("09:31:59", "$142.62", "420", "매도")),
                "현지 시간", "가격", "체결량", "구분");
        TabPane detail = new TabPane(tab("차트", createChartPreview("NVDA 일봉 차트 UI")), tab("10호가", orderBook),
                tab("체결", trades), tab("기업정보", createCompanyInfoPanel()));
        detail.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); detail.setPrefHeight(390);
        VBox panel = new VBox(14, name, price, metrics, detail); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createUsScannerPanel() {
        ComboBox<String> exchange = new ComboBox<>(FXCollections.observableArrayList("NASDAQ", "NYSE", "AMEX")); exchange.setValue("NASDAQ");
        ComboBox<String> condition = new ComboBox<>(FXCollections.observableArrayList("등락률", "거래량", "거래대금", "시가총액", "신고가", "갭", "연속상승"));
        condition.setValue("등락률");
        FlowPane filters = wrappingRow(10, labeledControl("거래소", exchange), labeledControl("조건", condition),
                primaryButton("조회", () -> status.setText("미국주식 스캐너를 갱신했습니다.")));
        filters.setAlignment(Pos.BOTTOM_LEFT);
        TableView<ObservableList<String>> table = textTable("미국주식 스캐너",
                List.of(row("1", "SMCI", "$724.30", "+8.42%", "12.8M", "거래량 급증"),
                        row("2", "NVDA", "$142.65", "+2.34%", "42.3M", "신고가 근접"),
                        row("3", "AMD", "$168.20", "+1.92%", "31.4M", "연속 상승")),
                "순위", "티커", "현재가", "등락률", "거래량", "신호");
        table.setPrefHeight(390); VBox panel = new VBox(16, filters, table); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createUsConditionPanel() {
        ComboBox<String> condition = new ComboBox<>(FXCollections.observableArrayList("미국 기술주 돌파", "ETF 거래량 급증", "52주 신고가"));
        condition.setValue("미국 기술주 돌파");
        CheckBox realtime = new CheckBox("실시간 감시"); realtime.setSelected(true);
        FlowPane controls = wrappingRow(12, condition, realtime, primaryButton("검색", () -> status.setText("미국 조건검색 결과 3건입니다.")));
        TableView<ObservableList<String>> table = textTable("미국 조건검색 결과",
                List.of(row("편입", "NVDA", "NVIDIA", "$142.65", "+2.34%"),
                        row("편입", "AMD", "AMD", "$168.20", "+1.92%"),
                        row("이탈", "TSLA", "Tesla", "$216.10", "-1.28%")),
                "상태", "티커", "종목", "현재가", "등락률");
        table.setPrefHeight(390); VBox panel = new VBox(16, controls, table); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createUsAccountPanel() {
        FlowPane metrics = wrappingRow(12, summaryCard("미국주식 평가금액", "$24,820.50", "+$1,420.30", "positive"),
                summaryCard("USD 예수금", "$3,280.40", "주문 가능 $3,120.00", "neutral"),
                summaryCard("원화 환산", "37,890,000원", "환율 1,348.20원", "neutral"));
        TableView<ObservableList<String>> holdings = textTable("미국주식 보유종목",
                List.of(row("NVDA", "40", "$118.20", "$142.65", "+$978.00", "+20.68%"),
                        row("AAPL", "25", "$210.40", "$228.40", "+$450.00", "+8.56%")),
                "티커", "수량", "평균단가", "현재가", "평가손익", "수익률");
        holdings.setPrefHeight(360); VBox panel = new VBox(16, metrics, holdings); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createUsWatchlistPanel() {
        return new WatchlistScreenView(watchlistViewModel, this::openWatchlistStock,
                () -> startWatchlistSearch("미국"), status::setText)
                .createUsPanel(selected -> showUsOrderConfirmation(selected.symbol(), "매수", 1,
                        selected.displayPrice().replace("$", "").replace(",", "")));
    }

    private VBox createUsOrderPanel() {
        ComboBox<String> side = new ComboBox<>(FXCollections.observableArrayList("매수", "매도")); side.setValue("매수");
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("지정가", "시장가")); type.setValue("지정가");
        TextField ticker = new TextField("NVDA"); TextField price = new TextField("142.50"); Spinner<Integer> quantity = new Spinner<>(1, 10000, 1);
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(12);
        addField(form, 0, "티커", ticker); addField(form, 1, "매수 / 매도", side); addField(form, 2, "주문 유형", type);
        addField(form, 3, "가격 USD", price); addField(form, 4, "수량", quantity);
        Label safety = new Label("미국주식 주문은 통화·현지 시장·예상 원화금액까지 재확인합니다."); safety.getStyleClass().add("safety-note"); safety.setWrapText(true);
        Button review = primaryButton("미국주식 주문 검토", () -> showUsOrderConfirmation(ticker.getText(), side.getValue(), quantity.getValue(), price.getText()));
        VBox newOrder = new VBox(14, safety, form, informationRow("예상 주문금액", "$142.50 · 약 192,119원"), review);
        newOrder.setPadding(new Insets(14));
        TableView<ObservableList<String>> open = textTable("미국주식 미체결",
                List.of(row("09:32", "NVDA", "매수", "$142.50", "2", "0", "2", "접수"),
                        row("09:18", "AAPL", "매도", "$229.00", "3", "1", "2", "부분체결")),
                "현지시간", "티커", "구분", "주문가", "수량", "체결", "잔여", "상태");
        Button amend = new Button("선택 주문 정정"); amend.setOnAction(event -> showUsAmendDialog(open));
        Button cancel = new Button("선택 주문 취소"); cancel.setOnAction(event -> cancelSelectedOrder(open));
        VBox openPanel = new VBox(10, open, wrappingRow(8, amend, cancel)); openPanel.setPadding(new Insets(10));
        TableView<ObservableList<String>> fills = textTable("미국주식 체결",
                List.of(row("09:11", "MSFT", "매수", "$446.80", "2", "$893.60", "체결"),
                        row("전일", "TSLA", "매도", "$218.20", "1", "$218.20", "체결")),
                "현지시간", "티커", "구분", "체결가", "수량", "금액", "상태");
        TabPane tabs = new TabPane(tab("신규 주문", newOrder), tab("미체결 · 정정 · 취소", openPanel), tab("체결", fills));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(480);
        VBox panel = new VBox(tabs);
        panel.setPadding(new Insets(18)); return panel;
    }

    private VBox createUsResearchPanel() {
        ListView<String> reports = new ListView<>(FXCollections.observableArrayList(
                "2026-08-10 · NVIDIA · AI 가속기 수요와 하반기 전망",
                "2026-08-08 · 반도체 산업 · 데이터센터 투자 사이클 점검",
                "2026-08-05 · Apple · 서비스 매출과 신제품 전망"));
        reports.setAccessibleText("미국주식 리서치 목록"); reports.setPrefHeight(330);
        TextArea summary = new TextArea("AI 가속기 수요가 데이터센터 투자를 중심으로 이어지고 있습니다. 다음 실적에서 공급 일정과 마진 추이를 확인해야 합니다. 출처: 키움 미국주식 리서치 UI 예시.");
        summary.setEditable(false); summary.setWrapText(true); summary.setPrefRowCount(5);
        Button listen = new Button("선택 리서치 듣기");
        listen.setOnAction(event -> requestSpeech(summary.getText(), "us-research-summary"));
        VBox panel = new VBox(14, reports, summary, listen); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createFxPanel() {
        TextField amount = new TextField("1000000");
        ComboBox<String> direction = new ComboBox<>(FXCollections.observableArrayList("KRW → USD", "USD → KRW")); direction.setValue("KRW → USD");
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(12);
        addField(form, 0, "환전 방향", direction); addField(form, 1, "환전 금액", amount);
        VBox panel = new VBox(16,
                wrappingRow(14, summaryCard("보유 KRW", "3,200,000원", "출금 가능 3,050,000원", "neutral"),
                        summaryCard("보유 USD", "$3,280.40", "주문 가능 $3,120.00", "neutral"),
                        summaryCard("현재 환율", "1,348.20원", "고시 환율 UI", "neutral")),
                form, informationRow("예상 수령", "$741.73"),
                primaryButton("환전 내용 검토", this::showFxDialog));
        panel.setPadding(new Insets(12)); return panel;
    }

    private ScrollPane createNotificationsScreen() {
        Label title = heading("알림");
        ComboBox<String> filter = new ComboBox<>(FXCollections.observableArrayList("전체", "주문", "가격", "이상 감지", "연결"));
        filter.setValue("전체");
        Button allRead = new Button("모두 읽음");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, filter, allRead); header.setAlignment(Pos.CENTER_LEFT);
        FilteredList<String> filtered = new FilteredList<>(session.notifications(), value -> true);
        filter.valueProperty().addListener((obs, old, selected) -> filtered.setPredicate(
                value -> selected == null || selected.equals("전체") || value.contains("· " + selected + " ·")));
        ListView<String> notifications = new ListView<>(filtered);
        notifications.setAccessibleText("알림 목록"); notifications.setPrefHeight(430);
        allRead.setOnAction(event -> {
            for (int i = 0; i < session.notifications().size(); i++) session.notifications().set(i, session.notifications().get(i).replace("새 알림 · ", ""));
            status.setText("모든 알림을 읽음 처리했습니다.");
        });
        Button listen = new Button("선택 알림 듣기");
        listen.setOnAction(event -> {
            String selected = notifications.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("들을 알림을 먼저 선택해주세요.");
                notifications.requestFocus();
                return;
            }
            requestSpeech(selected, "notification-selected");
        });
        Button markRead = new Button("선택 읽음"); markRead.setOnAction(event -> {
            String selected = notifications.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("읽음 처리할 알림을 선택해주세요.");
                notifications.requestFocus();
                return;
            }
            int index = session.notifications().indexOf(selected);
            if (index >= 0) session.notifications().set(index, selected.replace("새 알림 · ", ""));
            status.setText("선택한 알림을 읽음 처리했습니다.");
        });
        Button delete = new Button("선택 삭제"); delete.setOnAction(event -> {
            String selected = notifications.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("삭제할 알림을 선택해주세요.");
                notifications.requestFocus();
                return;
            }
            session.notifications().remove(selected);
            status.setText("선택한 알림을 삭제했습니다.");
        });
        VBox history = new VBox(10, notifications, wrappingRow(8, listen, markRead, delete)); history.setPadding(new Insets(10));

        TableView<AlertRule> rules = typedTable("알림 규칙", session.alertRules(),
                textColumn("종목", AlertRule::securityName),
                textColumn("조건", AlertRule::condition),
                textColumn("기준", AlertRule::threshold),
                textColumn("상태", AlertRule::statusText));
        Button addRule = new Button("알림 규칙 추가"); addRule.setOnAction(event -> showAlertRuleDialog(null));
        Button editRule = new Button("선택 수정"); editRule.setOnAction(event -> {
            AlertRule selected = rules.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("수정할 알림 규칙을 선택해주세요.");
                rules.requestFocus();
                return;
            }
            showAlertRuleDialog(selected);
        });
        Button toggleRule = new Button("활성·일시정지"); toggleRule.setOnAction(event -> {
            AlertRule selected = rules.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("상태를 바꿀 알림 규칙을 선택해주세요.");
                rules.requestFocus();
                return;
            }
            int index = session.alertRules().indexOf(selected);
            if (index >= 0) session.alertRules().set(index, selected.toggled());
            status.setText(selected.securityName() + " 알림 규칙 상태를 변경했습니다.");
        });
        Button deleteRule = new Button("선택 삭제");
        deleteRule.setOnAction(event -> deleteSelectedAlertRule(rules));
        VBox rulePanel = new VBox(10, rules, wrappingRow(8, addRule, editRule, toggleRule, deleteRule)); rulePanel.setPadding(new Insets(10));
        TabPane tabs = new TabPane(tab("알림 기록", history), tab("알림 규칙", rulePanel));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(530);
        return scrollPage("알림", new VBox(18, header, tabs));
    }

    private TableColumn<PricePoint, String> priceColumn(String title, java.util.function.Function<PricePoint, String> mapper) {
        TableColumn<PricePoint, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue()))); return column;
    }

    private List<PricePoint> dailyPriceHistory(String symbol, int count) {
        return candleAdapter.getCandles(symbol, CandleInterval.DAY, count).stream()
                .map(candle -> candle.toPricePoint(java.time.ZoneId.of("Asia/Seoul")))
                .toList();
    }

    private VBox createPortfolio(Account snapshot) {
        Label summary = new Label("총 평가금액 " + Formatters.won(snapshot.totalMarketValue())
                + " · 주문 가능 현금 " + Formatters.won(snapshot.balance().available())); summary.setWrapText(true);
        TableView<Position> table = new TableView<>(FXCollections.observableArrayList(snapshot.positions()));
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

    private TableColumn<Position, String> column(String title, java.util.function.Function<Position, String> mapper) {
        TableColumn<Position, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue()))); return column;
    }

    private VBox createOrderForm() {
        var selected = stockDetailViewModel.selection();
        OrderDraft draft = orderDraft == null
                ? new OrderDraft(selected.symbol(), selected.name(), OrderSide.BUY,
                OrderType.LIMIT, 1, pendingOrderPrice, Screen.DASHBOARD)
                : orderDraft;
        orderDraft = draft;
        TextField symbol = new TextField(draft.symbol()); TextField name = new TextField(draft.name());
        symbol.setEditable(false); name.setEditable(false);
        symbol.setAccessibleHelp("종목을 바꾸려면 종목검색에서 다른 종목을 선택해주세요.");
        name.setAccessibleHelp(symbol.getAccessibleHelp());
        ComboBox<OrderSide> side = new ComboBox<>(FXCollections.observableArrayList(OrderSide.values())); side.setValue(draft.side());
        side.setConverter(new StringConverter<>() {
            @Override public String toString(OrderSide value) { return value == null ? "" : value.displayName(); }
            @Override public OrderSide fromString(String value) { return OrderSide.valueOf(value); }
        });
        ComboBox<OrderType> orderType = new ComboBox<>(FXCollections.observableArrayList(OrderType.values()));
        orderType.setValue(draft.type());
        orderType.setConverter(new StringConverter<>() {
            @Override public String toString(OrderType value) { return value == null ? "" : value.displayName(); }
            @Override public OrderType fromString(String value) { return OrderType.valueOf(value); }
        });
        Spinner<Integer> quantity = new Spinner<>(1, 1_000_000, draft.quantity()); quantity.setEditable(true);
        TextField price = new TextField(draft.price());
        price.setDisable(draft.type() == OrderType.MARKET);
        side.valueProperty().addListener((obs, old, selectedSide) ->
                updateOrderDraft(draft, selectedSide, orderType.getValue(), quantity.getValue(), price.getText()));
        orderType.valueProperty().addListener((obs, old, selectedType) -> {
            price.setDisable(selectedType == OrderType.MARKET);
            updateOrderDraft(draft, side.getValue(), selectedType, quantity.getValue(), price.getText());
        });
        GridPane form = new GridPane(); form.setHgap(10); form.setVgap(10);
        addField(form, 0, "종목 코드", symbol); addField(form, 1, "종목명", name);
        addField(form, 2, "매수 / 매도", side); addField(form, 3, "주문 유형", orderType);
        addField(form, 4, "가격", price); addField(form, 5, "수량", quantity);

        HBox ratios = new HBox(8);
        for (int ratio : List.of(10, 25, 50, 100)) {
            Button button = new Button(ratio + "%");
            button.setOnAction(event -> quantity.getValueFactory().setValue(Math.max(1, ratio / 5)));
            ratios.getChildren().add(button);
        }
        Label estimatedAmount = new Label();
        Runnable updateEstimate = () -> {
            try {
                BigDecimal unitPrice = new BigDecimal(price.getText().replace(",", "").trim());
                estimatedAmount.setText(stockDetailViewModel.formatPrice(unitPrice.multiply(BigDecimal.valueOf(quantity.getValue()))));
            } catch (RuntimeException invalid) {
                estimatedAmount.setText("가격을 확인하세요");
            }
        };
        price.textProperty().addListener((obs, old, value) -> {
            updateEstimate.run();
            updateOrderDraft(draft, side.getValue(), orderType.getValue(), quantity.getValue(), value);
        });
        quantity.valueProperty().addListener((obs, old, value) -> {
            updateEstimate.run();
            updateOrderDraft(draft, side.getValue(), orderType.getValue(), value, price.getText());
        });
        updateEstimate.run();
        VBox estimates = new VBox(8,
                informationRow("주문 예상금액", estimatedAmount),
                informationRow("주문 가능금액", "7,820,000원"));
        estimates.getStyleClass().add("estimate-box"); estimates.setPadding(new Insets(12));
        Button preview = new Button("주문 내용 검토"); preview.getStyleClass().add("primary-button"); preview.setDefaultButton(true);
        preview.setAccessibleHelp("주문을 제출하지 않고 재확인 창을 엽니다.");
        preview.setOnAction(event -> previewOrder(symbol, name, side, orderType, quantity, price));
        VBox box = new VBox(14, sectionHeading("모의주문 준비"), form, new Label("주문 비율"), ratios, estimates, preview);
        box.getStyleClass().add("panel-card"); box.setPadding(new Insets(20));
        return box;
    }

    private void updateOrderDraft(OrderDraft initial, OrderSide side, OrderType type,
                                  Integer quantity, String price) {
        if (side == null || type == null || quantity == null || quantity <= 0
                || price == null || price.isBlank()) return;
        orderDraft = new OrderDraft(initial.symbol(), initial.name(), side, type, quantity, price, initial.origin());
        pendingOrderPrice = orderDraft.price();
    }

    private ScrollPane createSettingsScreen() {
        CheckBox tts = setting("화면 읽기(TTS)", speechEnabled, selected -> {
            speechEnabled = selected;
            if (selected) announce("음성 안내를 시작합니다.", SpeechPriority.USER_REQUEST, "speech-enabled");
            else speechQueue.clear();
            scheduleStateSave();
        });
        CheckBox keyboard = setting("키보드 탐색 안내", keyboardGuidanceEnabled,
                selected -> { keyboardGuidanceEnabled = selected; scheduleStateSave(); });
        CheckBox reducedMotion = setting("애니메이션 줄이기", reducedMotionEnabled,
                selected -> { reducedMotionEnabled = selected; scheduleStateSave(); });
        CheckBox anomalySound = setting("이상 감지 소리", soundEnabled, selected -> { soundEnabled = selected; scheduleStateSave(); });
        CheckBox largeText = setting("큰 글자", largeTextEnabled, selected -> {
            largeTextEnabled = selected;
            toggleClass("large-text", selected);
            if (sidebarRoot != null) {
                sidebarRoot.setPrefWidth(selected ? 250 : 216);
                sidebarRoot.setMinWidth(selected ? 230 : 200);
            }
            scheduleStateSave();
        });
        CheckBox contrast = setting("고대비", highContrastEnabled, selected -> {
            highContrastEnabled = selected;
            toggleClass("high-contrast", selected);
            scheduleStateSave();
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
            scheduleStateSave();
        });
        loadVoices(voice, systemDefault);

        ComboBox<String> speed = new ComboBox<>(FXCollections.observableArrayList("0.8배", "1.0배", "1.2배", "1.5배"));
        speed.setValue(String.format(java.util.Locale.ROOT, "%.1f배", speechQueue.options().rate()));
        speed.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null && !speechQueue.isClosed()) {
                double rate = Double.parseDouble(selected.replace("배", ""));
                speechQueue.setOptions(speechQueue.options().withRate(rate));
                scheduleStateSave();
            }
        });
        Slider volume = new Slider(0, 100, speechQueue.options().volume()); volume.setShowTickLabels(true); volume.setMajorTickUnit(25);
        volume.valueProperty().addListener((obs, old, selected) -> {
            if (!speechQueue.isClosed()) {
                speechQueue.setOptions(speechQueue.options().withVolume(selected.intValue())); scheduleStateSave();
            }
        });
        ComboBox<String> density = new ComboBox<>(FXCollections.observableArrayList("간단히", "표준", "자세히"));
        density.setValue(informationDensity);
        density.valueProperty().addListener((obs, old, selected) -> {
            if (selected != null) { informationDensity = selected; scheduleStateSave(); }
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
        Button auditAccessibility = new Button("현재 화면 접근성 검사");
        auditAccessibility.setOnAction(event -> {
            List<AccessibilityAudit.Issue> issues = new AccessibilityAudit().audit(root);
            if (issues.isEmpty()) {
                showInformation("접근성 검사 통과", "현재 생성된 화면에서 접근 가능한 이름 누락을 찾지 못했습니다.");
                return;
            }
            String details = issues.stream().limit(8).map(AccessibilityAudit.Issue::message)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            showInformation("접근성 검사 결과 " + issues.size() + "건", details);
        });
        VBox settings = new VBox(8, tts, keyboard, reducedMotion, anomalySound, largeText, contrast);
        settings.getStyleClass().add("settings-card");
        VBox accessibility = new VBox(18, sectionHeading("접근성"), settings, sectionHeading("음성 설정"),
                voiceSettings, wrappingRow(8, preview, auditAccessibility)); accessibility.setPadding(new Insets(18));

        VBox kiwoom = new VBox(14, sectionHeading("키움 API 연결"),
                informationRow("현재 환경", "로컬 모의 UI"), informationRow("REST", "미연결"),
                informationRow("실시간", "미연결"), informationRow("토큰", "발급 전"),
                primaryButton("연결 설정 열기", () -> navigate(Screen.CONNECTION)),
                new Label("App Secret은 화면이나 일반 설정 파일에 표시·저장하지 않습니다."));
        kiwoom.setPadding(new Insets(18));

        CheckBox confirm = new CheckBox("모든 실전 신규·정정·취소 주문 재확인"); confirm.setSelected(true); confirm.setDisable(true);
        CheckBox preventDuplicate = new CheckBox("주문 버튼 연속 입력 방지"); preventDuplicate.setSelected(preventDuplicateOrders);
        preventDuplicate.selectedProperty().addListener((obs, old, value) -> {
            preventDuplicateOrders = value; scheduleStateSave();
        });
        ComboBox<String> defaultAccount = new ComboBox<>(FXCollections.observableArrayList("모의계좌 ****-1204", "실전계좌 ****-8821"));
        defaultAccount.setValue("모의계좌 ****-1204");
        VBox trading = new VBox(14, sectionHeading("거래 안전"), confirm, preventDuplicate,
                labeledControl("기본 계좌", defaultAccount), informationRow("주문 확인 유효시간", "30초"),
                informationRow("주문 조회 제한", "초당 5회")); trading.setPadding(new Insets(18));

        Slider subscriptionsSlider = new Slider(20, 200, maxSubscriptions); subscriptionsSlider.setShowTickLabels(true); subscriptionsSlider.setMajorTickUnit(20);
        subscriptionsSlider.valueProperty().addListener((obs, old, value) -> {
            maxSubscriptions = value.intValue(); scheduleStateSave();
        });
        TableView<ObservableList<String>> priorities = textTable("실시간 구독 우선순위",
                List.of(row("1", "현재 열어둔 종목", "항상"), row("2", "주문 중인 종목", "항상"),
                        row("3", "보유 종목", "높음"), row("4", "관심종목", "보통"), row("5", "조건검색 결과", "낮음")),
                "우선순위", "대상", "정책"); priorities.setPrefHeight(280);
        VBox realtime = new VBox(14, sectionHeading("실시간 데이터"), labeledControl("최대 구독 종목", subscriptionsSlider),
                priorities, new Label("화면이 닫힌 종목은 자동으로 구독 해제합니다.")); realtime.setPadding(new Insets(18));

        VBox security = new VBox(14, sectionHeading("보안"),
                informationRow("Windows 비밀 저장", "DPAPI 사용"), informationRow("모의/실전 자격증명", "완전 분리"),
                informationRow("로그 계좌번호", "마스킹"), informationRow("토큰 평문 저장", "사용 안 함"),
                new Label("실전 App Key, Secret, 토큰, 계좌 비밀번호는 SQLite에 저장하지 않습니다."));
        security.setPadding(new Insets(18));

        TabPane tabs = new TabPane(tab("접근성", accessibility), tab("키움 연결", kiwoom), tab("거래", trading),
                tab("실시간", realtime), tab("보안", security), tab("화면 상태", createUiStatePanel()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(620);
        return scrollPage("설정", new VBox(18, heading("설정"), tabs));
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

    private Button linkButton(String text, Screen screen) {
        Button button = new Button(text); button.getStyleClass().add("link-button");
        button.setOnAction(event -> navigate(screen)); return button;
    }

    private VBox createBrokerAnalysisPanel() {
        TableView<ObservableList<String>> buy = textTable("매수 상위 거래원",
                List.of(row("키움", "1,200,000", "28.4%"), row("미래", "980,000", "23.2%"), row("NH", "820,000", "19.4%")),
                "매수 거래원", "수량", "비중");
        TableView<ObservableList<String>> sell = textTable("매도 상위 거래원",
                List.of(row("삼성", "1,040,000", "24.8%"), row("KB", "910,000", "21.7%"), row("한국", "760,000", "18.1%")),
                "매도 거래원", "수량", "비중");
        buy.setPrefHeight(250); sell.setPrefHeight(250);
        SplitPane tables = new SplitPane(buy, sell); tables.setDividerPositions(0.5);
        buy.setMinWidth(0); sell.setMinWidth(0);
        VBox panel = new VBox(14, sectionHeading("삼성전자 거래원 분석"), tables); panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createStockProgramPanel() {
        TableView<ObservableList<String>> table = textTable("삼성전자 프로그램매매",
                List.of(row("14:30", "+18,420", "-12,100", "+6,320", "+42.8억"),
                        row("14:00", "+15,820", "-11,240", "+4,580", "+31.2억"),
                        row("13:30", "+12,210", "-9,840", "+2,370", "+16.1억")),
                "시간", "매수", "매도", "순매수", "순매수 금액");
        table.setPrefHeight(320);
        VBox panel = new VBox(12, wrappingRow(12, summaryCard("당일 프로그램 순매수", "+42.8억원", "+6,320주", "positive"),
                summaryCard("5일 누적", "+184억원", "순매수 지속", "positive")), table);
        panel.setPadding(new Insets(12)); return panel;
    }

    private VBox createChartPreview(String accessibleName) {
        List<PricePoint> points = dailyPriceHistory("005930", 30);
        CandlestickChartView chart = new CandlestickChartView(points);
        chart.setAccessibleText(accessibleName + ". " + chart.getAccessibleText());
        CheckBox movingAverage = new CheckBox("이동평균"); movingAverage.setSelected(true);
        CheckBox rsi = new CheckBox("RSI"); CheckBox macd = new CheckBox("MACD"); CheckBox bollinger = new CheckBox("Bollinger Band");
        movingAverage.selectedProperty().addListener((obs, old, value) -> chart.setShowMovingAverages(value));
        rsi.selectedProperty().addListener((obs, old, value) -> chart.setShowRsi(value));
        macd.selectedProperty().addListener((obs, old, value) -> chart.setShowMacd(value));
        bollinger.selectedProperty().addListener((obs, old, value) -> chart.setShowBollinger(value));
        FlowPane indicators = wrappingRow(8, movingAverage, rsi, macd, bollinger);
        VBox panel = new VBox(12, indicators, chart); panel.setPadding(new Insets(10)); return panel;
    }

    private GridPane createCompanyInfoPanel() {
        GridPane grid = new GridPane(); grid.setHgap(28); grid.setVgap(16); grid.setPadding(new Insets(22));
        addInfo(grid, 0, 0, "기업", "NVIDIA Corporation"); addInfo(grid, 1, 0, "산업", "Semiconductors");
        addInfo(grid, 0, 1, "시가총액", "$3.5T"); addInfo(grid, 1, 1, "PER", "55.2배");
        addInfo(grid, 0, 2, "52주 최고", "$152.89"); addInfo(grid, 1, 2, "52주 최저", "$45.01");
        return grid;
    }

    private String selectedName(TableView<ObservableList<String>> table, int index, String fallback) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        return selected == null || selected.size() <= index ? fallback : selected.get(index);
    }

    private void sortMarketRows(TableView<ObservableList<String>> table, String criterion) {
        int column = switch (criterion) {
            case "거래량" -> 4;
            case "거래대금" -> 5;
            default -> 3;
        };
        java.util.Comparator<ObservableList<String>> comparator = java.util.Comparator.comparingDouble(
                row -> numericMagnitude(row.get(column)));
        if (!criterion.equals("하락률")) comparator = comparator.reversed();
        FXCollections.sort(table.getItems(), comparator);
        for (int i = 0; i < table.getItems().size(); i++) table.getItems().get(i).set(0, Integer.toString(i + 1));
        table.refresh(); status.setText(criterion + " 기준으로 시장 종목을 정렬했습니다.");
    }

    private double numericMagnitude(String value) {
        if (value == null) return 0;
        double multiplier = value.contains("조") ? 1_000_000_000_000d : value.contains("억") ? 100_000_000d : 1d;
        String normalized = value.replaceAll("[^0-9.+-]", "");
        try { return normalized.isBlank() ? 0 : Double.parseDouble(normalized) * multiplier; }
        catch (NumberFormatException ignored) { return 0; }
    }

    private void showProductDetail(String productType, String productName) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle(productType + " 상세");
        dialog.setHeaderText(productName);
        CandlestickChartView chart = new CandlestickChartView(dailyPriceHistory("005930", 30));
        TableView<ObservableList<String>> quote = textTable(productName + " 호가",
                List.of(row("매도", "36,140원", "8,240"), row("매도", "36,130원", "12,410"),
                        row("매수", "36,120원", "9,820"), row("매수", "36,110원", "14,200")),
                "구분", "가격", "잔량");
        GridPane info = new GridPane(); info.setHgap(24); info.setVgap(14); info.setPadding(new Insets(18));
        addInfo(info, 0, 0, "상품 구분", productType); addInfo(info, 1, 0, "현재가", "36,120원");
        addInfo(info, 0, 1, "등락률", "+1.02%"); addInfo(info, 1, 1, "거래량", "1,284,220");
        addInfo(info, 0, 2, "위험 등급", productType.equals("ELW") ? "매우 높음" : "보통");
        TabPane tabs = new TabPane(tab("차트", chart), tab("호가", quote), tab("상품정보", info));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(480);
        Button buy = primaryButton("매수 검토", () -> showCommodityOrderConfirmation(productName, "매수"));
        Button sell = new Button("매도 검토"); sell.setOnAction(event -> showCommodityOrderConfirmation(productName, "매도"));
        VBox content = new VBox(12, tabs, wrappingRow(8, buy, sell)); content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        double maxWidth = javafx.stage.Screen.getPrimary().getVisualBounds().getWidth() * 0.82;
        double maxHeight = javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.82;
        dialog.getDialogPane().setPrefSize(Math.min(920, maxWidth), Math.min(680, maxHeight));
        dialog.showAndWait();
    }

    private VBox createCreditTradingPanel() {
        Label warning = stateBanner("신용거래는 원금 초과 손실 가능성이 있습니다. 위험 안내를 확인해야 주문 검토가 활성화됩니다.", "warning");
        CheckBox consent = new CheckBox("신용거래 위험과 이자 비용을 확인했습니다.");
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("유통융자", "자기융자", "대주")); type.setValue("유통융자");
        TextField security = new TextField("삼성전자"); TextField price = new TextField("72,500");
        Spinner<Integer> quantity = new Spinner<>(1, 100_000, 10);
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(10);
        addField(form, 0, "신용 구분", type); addField(form, 1, "종목", security); addField(form, 2, "가격", price); addField(form, 3, "수량", quantity);
        Button review = primaryButton("신용주문 검토", () -> showCommodityOrderConfirmation(security.getText(), "신용 매수"));
        review.disableProperty().bind(consent.selectedProperty().not());
        VBox panel = new VBox(14, warning, wrappingRow(12, summaryCard("신용 한도", "10,000,000원", "사용 0원", "neutral"),
                summaryCard("적용 이율", "연 7.5%", "UI 예시", "neutral"), summaryCard("상환 기한", "90일", "종목별 상이", "neutral")),
                consent, form, informationRow("예상 신용 주문금액", "725,000원"), review);
        panel.setPadding(new Insets(14)); return panel;
    }

    private void showUsOrderConfirmation(String ticker, String side, int quantity, String price) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("미국주식 모의주문 재확인");
        confirmation.setHeaderText(ticker + " " + quantity + "주를 " + side + "하시겠습니까?");
        confirmation.setContentText("지정가: $" + price + "\n예상 주문금액: $" + price
                + "\n예상 원화금액: 약 192,119원\n계좌: 미국주식 모의계좌 ****-7781\n\nUI 데모이며 실제 주문은 전송되지 않습니다.");
        confirmation.showAndWait();
    }

    private void showUsAmendDialog(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInformation("주문을 선택하세요", "정정할 미국주식 미체결 주문을 선택해주세요.");
            return;
        }
        TextInputDialog priceDialog = new TextInputDialog(selected.get(3).replace("$", ""));
        priceDialog.setTitle("미국주식 모의주문 정정");
        priceDialog.setHeaderText(selected.get(1) + " 정정 가격을 USD로 입력하세요.");
        priceDialog.setContentText("정정 가격");
        priceDialog.showAndWait().ifPresent(value -> {
            try {
                BigDecimal parsed = new BigDecimal(value.trim());
                if (parsed.signum() <= 0) throw new IllegalArgumentException();
                selected.set(3, "$" + parsed.setScale(2, java.math.RoundingMode.HALF_UP));
                selected.set(7, "정정 접수"); table.refresh();
                status.setText(selected.get(1) + " 미국주식 주문 정정을 모의 접수했습니다.");
            } catch (RuntimeException invalid) {
                showInformation("가격을 확인하세요", "0보다 큰 숫자를 입력해주세요.");
            }
        });
    }

    private void showAlertRuleDialog(AlertRule existing) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle(existing == null ? "알림 규칙 추가" : "알림 규칙 수정");
        TextField security = new TextField(existing == null ? "" : existing.securityName());
        ComboBox<String> condition = new ComboBox<>(FXCollections.observableArrayList("가격 이상", "가격 이하", "등락률 이상", "거래량 급증", "VI", "조건검색 편입"));
        condition.setValue(existing == null ? "가격 이상" : existing.condition());
        TextField threshold = new TextField(existing == null ? "" : existing.threshold());
        CheckBox enabled = new CheckBox("규칙 활성화"); enabled.setSelected(existing == null || existing.enabled());
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(10);
        addField(form, 0, "종목", security); addField(form, 1, "조건", condition); addField(form, 2, "기준", threshold);
        form.add(enabled, 0, 3, 2, 1); dialog.getDialogPane().setContent(form);
        ButtonType save = new ButtonType("저장", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            if (security.getText().isBlank() || threshold.getText().isBlank()) {
                showInformation("입력값을 확인하세요", "종목과 기준값은 필수입니다."); return;
            }
            AlertRule replacement = new AlertRule(
                    security.getText(), condition.getValue(), threshold.getText(), enabled.isSelected());
            if (existing == null) session.alertRules().add(replacement);
            else {
                int index = session.alertRules().indexOf(existing);
                if (index >= 0) session.alertRules().set(index, replacement);
            }
        });
    }

    private void deleteSelectedAlertRule(TableView<AlertRule> table) {
        AlertRule selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("삭제할 알림 규칙을 선택해주세요.");
            table.requestFocus();
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                selected.securityName() + " 알림 규칙을 삭제하시겠습니까?", ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("알림 규칙 삭제");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            session.alertRules().remove(selected);
            status.setText(selected.securityName() + " 알림 규칙을 삭제했습니다.");
        });
    }

    private void showJournalDialog(JournalEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle(existing == null ? "매매일지 작성" : "매매일지 수정");
        TextField date = new TextField(existing == null ? "08/10" : existing.date());
        TextField security = new TextField(existing == null ? "" : existing.securityName());
        TextField buy = new TextField(existing == null ? "0원" : existing.buyAmount());
        TextField sell = new TextField(existing == null ? "0원" : existing.sellAmount());
        TextField profit = new TextField(existing == null ? "0원" : existing.profitLoss());
        TextArea strategy = new TextArea(existing == null ? "" : existing.memo()); strategy.setPrefRowCount(3); strategy.setWrapText(true);
        TextField tags = new TextField(existing == null ? "" : existing.tags());
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(10);
        addField(form, 0, "날짜", date); addField(form, 1, "종목", security); addField(form, 2, "매수금액", buy);
        addField(form, 3, "매도금액", sell); addField(form, 4, "손익", profit); addField(form, 5, "전략·메모", strategy);
        addField(form, 6, "태그", tags); dialog.getDialogPane().setContent(form);
        ButtonType save = new ButtonType("저장", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            if (date.getText().isBlank() || security.getText().isBlank()) {
                showInformation("입력값을 확인하세요", "날짜와 종목은 필수입니다."); return;
            }
            JournalEntry replacement = new JournalEntry(date.getText(), security.getText(), buy.getText(),
                    sell.getText(), profit.getText(), strategy.getText(), tags.getText());
            if (existing == null) session.journalEntries().add(0, replacement);
            else {
                int index = session.journalEntries().indexOf(existing);
                if (index >= 0) session.journalEntries().set(index, replacement);
            }
            status.setText(security.getText().trim() + " 매매일지를 저장했습니다.");
        });
    }

    private void deleteSelectedJournal(TableView<JournalEntry> table) {
        JournalEntry selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("삭제할 매매일지를 선택해주세요.");
            table.requestFocus();
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                selected.date() + " · " + selected.securityName() + " 일지를 삭제하시겠습니까?",
                ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("매매일지 삭제");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            session.journalEntries().remove(selected);
            status.setText(selected.securityName() + " 매매일지를 삭제했습니다.");
        });
    }

    private void showCommodityOrderConfirmation(String product, String side) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle(product + " 모의주문 재확인");
        confirmation.setHeaderText(product + " 10g을 " + side + "하시겠습니까?");
        confirmation.setContentText("가격: 128,450원/g\n예상 주문금액: 1,284,500원\n계좌: 금현물 모의계좌 ****-1204\n\nUI 데모이며 실제 주문은 전송되지 않습니다.");
        confirmation.showAndWait();
    }

    private TableView<ObservableList<String>> orderStatusTable(boolean open) {
        if (open) {
            return textTable("미체결 주문",
                    List.of(row("14:30", "삼성전자", "매수", "72,400원", "10", "5", "5", "부분체결"),
                            row("14:18", "SK하이닉스", "매수", "183,000원", "3", "0", "3", "접수"),
                            row("14:02", "현대차", "매도", "281,000원", "2", "0", "2", "결과 확인 중")),
                    "시간", "종목", "구분", "주문가", "수량", "체결", "잔여", "상태");
        }
        return textTable("체결 주문",
                List.of(row("14:22", "NAVER", "매도", "시장가", "3", "3", "205,000원", "체결"),
                        row("13:05", "삼성전자", "매수", "71,800원", "5", "5", "71,800원", "체결"),
                        row("12:48", "카카오", "매수", "44,800원", "10", "0", "-", "거부 · 주문가능금액 부족")),
                "시간", "종목", "구분", "주문가", "수량", "체결", "체결가", "상태");
    }

    private VBox createUiStatePanel() {
        ComboBox<String> state = new ComboBox<>(FXCollections.observableArrayList(
                "정상", "로딩 중", "데이터 없음", "API 오류", "연결 끊김", "오래된 데이터", "재연결 중"));
        state.setValue("정상"); state.setAccessibleText("미리 볼 화면 상태");
        StackPane host = new StackPane(); host.setMinHeight(360); host.getStyleClass().add("state-preview");
        Runnable render = () -> host.getChildren().setAll(createStateContent(state.getValue(), state));
        state.valueProperty().addListener((obs, old, selected) -> render.run()); render.run();
        VBox panel = new VBox(14, sectionHeading("화면 상태 구성요소"),
                new Label("각 화면에서 사용할 로딩·빈 데이터·오류·연결 상태를 키보드와 스크린리더로 확인할 수 있습니다."),
                state, host);
        panel.setPadding(new Insets(18)); return panel;
    }

    private Node createStateContent(String state, ComboBox<String> selector) {
        if (state == null || state.equals("정상")) {
            TableView<ObservableList<String>> table = textTable("정상 데이터 예시",
                    List.of(row("삼성전자", "72,500원", "+2.12%"), row("SK하이닉스", "184,500원", "+1.42%")),
                    "종목", "현재가", "등락률"); table.setPrefHeight(260); return table;
        }
        if (state.equals("로딩 중")) {
            ProgressIndicator progress = new ProgressIndicator(); progress.setAccessibleText("데이터를 불러오는 중");
            ProgressBar first = new ProgressBar(-1); first.setPrefWidth(320);
            ProgressBar second = new ProgressBar(-1); second.setPrefWidth(260);
            return centeredState(progress, "데이터를 불러오고 있습니다.", first, second);
        }
        if (state.equals("데이터 없음")) {
            Button action = new Button("종목 검색"); action.setOnAction(event -> navigate(Screen.SEARCH));
            return centeredState(null, "표시할 데이터가 없습니다.", new Label("필터를 바꾸거나 관심종목을 추가해보세요."), action);
        }
        if (state.equals("API 오류")) {
            Button retry = new Button("다시 시도"); retry.setOnAction(event -> simulateRetry(selector));
            return centeredState(null, "데이터를 불러오지 못했습니다.", stateBanner("키움 API 응답이 지연되고 있습니다. 주문 상태는 별도로 확인합니다.", "error"), retry);
        }
        if (state.equals("연결 끊김")) {
            Button reconnect = new Button("재연결"); reconnect.setOnAction(event -> simulateRetry(selector));
            return centeredState(null, "실시간 연결이 끊겼습니다.", stateBanner("마지막 정상 데이터 14:28:03 · 실전 주문 차단", "error"), reconnect);
        }
        if (state.equals("오래된 데이터")) {
            Button refresh = new Button("새로고침"); refresh.setOnAction(event -> simulateRetry(selector));
            return centeredState(null, "표시 중인 데이터가 오래되었습니다.", stateBanner("마지막 계좌 동기화 12분 전 · 주문 전 새로고침 필요", "warning"), refresh);
        }
        ProgressIndicator progress = new ProgressIndicator();
        return centeredState(progress, "실시간 데이터에 재연결하고 있습니다.", stateBanner("재연결 후 미체결·체결·잔고를 다시 확인합니다.", "warning"));
    }

    private VBox centeredState(Node icon, String title, Node... details) {
        Label heading = sectionHeading(title); heading.setWrapText(true);
        VBox box = new VBox(14); if (icon != null) box.getChildren().add(icon);
        box.getChildren().add(heading); box.getChildren().addAll(details);
        box.setAlignment(Pos.CENTER); box.setPadding(new Insets(28)); box.setAccessibleText(title); return box;
    }

    private void simulateRetry(ComboBox<String> selector) {
        selector.setValue("로딩 중");
        PauseTransition transition = new PauseTransition(Duration.millis(700));
        transition.setOnFinished(event -> selector.setValue("정상")); transition.play();
    }

    private void showAmendOrderDialog(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInformation("주문을 선택하세요", "정정할 미체결 주문을 먼저 선택해주세요.");
            return;
        }
        if (selected.get(7).contains("결과 확인")) {
            showInformation("정정할 수 없습니다", "주문 결과를 확인 중입니다. 상태 동기화가 끝난 뒤 다시 시도해주세요.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("모의주문 정정");
        dialog.setHeaderText(selected.get(1) + " 주문을 정정합니다.");
        TextField price = new TextField(selected.get(3).replaceAll("[^0-9.]", ""));
        Spinner<Integer> quantity = new Spinner<>(1, 1_000_000, Integer.parseInt(selected.get(6)));
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(12);
        form.add(informationRow("기존 주문", selected.get(3) + " · 잔여 " + selected.get(6) + "주"), 0, 0, 2, 1);
        form.add(new Label("정정 가격"), 0, 1); form.add(price, 1, 1);
        form.add(new Label("정정 수량"), 0, 2); form.add(quantity, 1, 2);
        form.add(stateBanner("정정 주문도 최종 확인 후 한 번만 제출됩니다.", "warning"), 0, 3, 2, 1);
        dialog.getDialogPane().setContent(form);
        ButtonType submit = new ButtonType("정정 확인", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(submit, ButtonType.CANCEL);
        dialog.showAndWait().filter(submit::equals).ifPresent(result -> {
            selected.set(3, Formatters.won(new BigDecimal(price.getText().replace(",", ""))));
            selected.set(4, Integer.toString(quantity.getValue()));
            selected.set(6, Integer.toString(quantity.getValue()));
            selected.set(7, "정정 접수");
            table.refresh();
            status.setText(selected.get(1) + " 주문 정정을 모의 접수했습니다.");
            announce(selected.get(1) + " 주문 정정이 접수되었습니다.", SpeechPriority.ORDER, "amend-" + selected.get(0));
        });
    }

    private void cancelSelectedOrder(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInformation("주문을 선택하세요", "취소할 미체결 주문을 먼저 선택해주세요.");
            return;
        }
        if (selected.get(7).contains("결과 확인")) {
            showInformation("취소할 수 없습니다", "주문 결과를 확인 중입니다. 중복 취소를 막기 위해 잠시 기다려주세요.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("모의주문 취소 재확인");
        confirmation.setHeaderText(selected.get(1) + " 잔여 " + selected.get(6) + "주를 취소하시겠습니까?");
        confirmation.setContentText("원주문 가격: " + selected.get(3) + "\n계좌: 모의계좌 ****-1204\n\n실제 주문은 전송되지 않습니다.");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            selected.set(6, "0"); selected.set(7, "취소"); table.refresh();
            status.setText(selected.get(1) + " 주문을 모의 취소했습니다.");
        });
    }

    private void cancelAllOrders(TableView<ObservableList<String>> table) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "결과 확인 중인 주문을 제외한 모든 미체결 주문을 취소하시겠습니까?", ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("미체결 전량 취소 재확인");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            table.getItems().stream().filter(row -> !row.get(7).contains("결과 확인")).forEach(row -> {
                row.set(6, "0"); row.set(7, "취소");
            });
            table.refresh(); status.setText("미체결 주문을 모의 전량 취소했습니다.");
        });
    }

    private void showInformation(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(title); alert.showAndWait();
    }

    private void showFxDialog() {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("환전 예상"); dialog.setHeaderText("KRW에서 USD로 환전");
        TextField amount = new TextField("1000000");
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(12);
        form.add(new Label("보유 KRW"), 0, 0); form.add(new Label("3,200,000원"), 1, 0);
        form.add(new Label("적용 환율"), 0, 1); form.add(new Label("1 USD = 1,348.20 KRW"), 1, 1);
        form.add(new Label("환전 금액"), 0, 2); form.add(amount, 1, 2);
        form.add(new Label("예상 수령"), 0, 3); form.add(new Label("약 741.73 USD"), 1, 3);
        Label note = new Label("UI 데모이며 실제 환전은 신청되지 않습니다."); note.getStyleClass().add("safety-note");
        form.add(note, 0, 4, 2, 1); dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().setAll(new ButtonType("환전 내용 확인", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        dialog.showAndWait();
    }

    /** 조회 결과의 등락률을 부호와 함께 표기한다. 값을 새로 만들지 않는다. */
    private static String signedChangeRate(StockDetail detail) {
        BigDecimal rate = detail.changeRate().setScale(2, java.math.RoundingMode.HALF_UP);
        String sign = detail.direction() == PriceDirection.DOWN ? "-" : rate.signum() > 0 ? "+" : "";
        return sign + rate.abs().toPlainString() + "%";
    }

    private void previewOrder(TextField symbol, TextField name, ComboBox<OrderSide> side,
                              ComboBox<OrderType> orderType, Spinner<Integer> quantity, TextField price) {
        try {
            BigDecimal referencePrice = stockDetailViewModel.detail().currentPrice();
            OrderCommand request = orderType.getValue() == OrderType.MARKET
                    ? OrderCommand.market(symbol.getText().trim(), name.getText().trim(), side.getValue(),
                            quantity.getValue())
                    : OrderCommand.limit(symbol.getText().trim(), name.getText().trim(), side.getValue(),
                            quantity.getValue(), new BigDecimal(price.getText().replace(",", "").trim()));
            TradePreview result = tradingUseCase.preview(request, referencePrice);
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("모의주문 재확인");
            confirmation.setHeaderText(request.name() + " " + request.quantity() + "주 " + request.side().displayName() + " 주문을 제출하시겠습니까?");
            String orderPrice = request.type() == OrderType.MARKET ? "시장가" : Formatters.won(request.limitPrice());
            confirmation.setContentText("종목 코드: " + request.symbol() + "\n주문 가격: " + orderPrice
                    + "\n예상 주문금액: " + Formatters.won(result.estimatedAmount())
                    + "\n주문 후 예상 현금: " + Formatters.won(result.availableCashAfter()) + "\n\n실제 주문이 아닌 모의주문입니다.");
            ButtonType submit = new ButtonType("모의 " + request.side().displayName() + " 제출", ButtonBar.ButtonData.OK_DONE);
            confirmation.getButtonTypes().setAll(submit, ButtonType.CANCEL);
            confirmation.showAndWait().filter(submit::equals).ifPresent(button -> {
                Order receipt = tradingUseCase.submitConfirmed(request, referencePrice);
                String receiptMessage = receipt.describe();
                status.setText(receiptMessage + " 주문번호 " + receipt.orderId());
                announce(receiptMessage, SpeechPriority.ORDER, "order-" + receipt.orderId()); play(SoundCue.SUCCESS);
                Alert completed = new Alert(Alert.AlertType.INFORMATION);
                completed.setTitle("모의주문 접수 결과");
                completed.setHeaderText(receiptMessage);
                completed.setContentText("주문번호: " + receipt.orderId() + "\n주문 화면에 머물거나 이전 화면으로 돌아갈 수 있습니다.");
                ButtonType back = new ButtonType("이전 화면으로 돌아가기", ButtonBar.ButtonData.BACK_PREVIOUS);
                ButtonType stay = new ButtonType("주문 화면 유지", ButtonBar.ButtonData.OK_DONE);
                completed.getButtonTypes().setAll(back, stay);
                completed.showAndWait().filter(back::equals).ifPresent(resultButton -> navigateBack());
            });
        } catch (RuntimeException exception) {
            status.setText("주문 입력 오류: " + exception.getMessage());
            announce("주문 입력 오류. " + exception.getMessage(), SpeechPriority.CRITICAL, "order-input-error"); play(SoundCue.ERROR);
            Alert alert = new Alert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK); alert.setHeaderText("주문 입력을 확인하세요."); alert.showAndWait();
        }
    }

    private HBox createStatusBar() {
        status.setWrapText(true);
        Label rest = new Label("REST 미연결"); rest.getStyleClass().addAll("status-item", "status-mock");
        Label realtime = new Label("실시간 미연결"); realtime.getStyleClass().addAll("status-item", "status-mock");
        Label mode = new Label("모의투자"); mode.getStyleClass().addAll("status-item", "status-mock");
        Label subscriptions = new Label("실시간 구독 0 / 200"); subscriptions.getStyleClass().add("status-item");
        lastDataTime.setText("데모 시세 · 로컬 스냅샷");
        lastDataTime.getStyleClass().add("status-item");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(14, status, spacer, rest, realtime, mode, subscriptions, lastDataTime);
        bar.setAlignment(Pos.CENTER_LEFT); bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(9, 16, 9, 16)); return bar;
    }

    private void restoreLocalState() {
        stateRepository.load().ifPresent(snapshot -> {
            session.restore(snapshot);
            preventDuplicateOrders = snapshot.preventDuplicateOrders();
            maxSubscriptions = snapshot.maxSubscriptions();
        });
        AccessibilityPreferences accessibility = accessibilityPreferencesRepository.load();
        speechEnabled = accessibility.speechEnabled();
        soundEnabled = accessibility.soundEnabled();
        keyboardGuidanceEnabled = accessibility.keyboardGuidanceEnabled();
        reducedMotionEnabled = accessibility.reducedMotionEnabled();
        largeTextEnabled = accessibility.largeTextEnabled();
        highContrastEnabled = accessibility.highContrastEnabled();
        informationDensity = accessibility.informationDensity();
        String voice = accessibility.voiceName().isBlank() ? null : accessibility.voiceName();
        speechQueue.setOptions(new SpeechOptions(accessibility.speechRate(), accessibility.speechVolume(), voice));
        sonificationPreferences = sonificationPreferencesRepository.load();
    }

    private void scheduleStateSave() {
        if (root == null) return;
        if (persistenceDelay == null) {
            persistenceDelay = new PauseTransition(Duration.millis(350));
            persistenceDelay.setOnFinished(event -> saveLocalState());
        }
        persistenceDelay.playFromStart();
    }

    private void saveLocalState() {
        try {
            SpeechOptions speech = speechQueue.options();
            stateRepository.save(new DesktopStateSnapshot(
                    List.copyOf(session.watchlistGroups()), List.copyOf(session.watchlistItems()),
                    List.copyOf(session.alertRules()), List.copyOf(session.notifications()),
                    List.copyOf(session.journalEntries()), session.selectedStock(),
                    preventDuplicateOrders, maxSubscriptions));
            accessibilityPreferencesRepository.save(new AccessibilityPreferences(
                    speechEnabled, soundEnabled, keyboardGuidanceEnabled, reducedMotionEnabled,
                    largeTextEnabled, highContrastEnabled, informationDensity,
                    speech.voiceName() == null ? "" : speech.voiceName(), speech.rate(), speech.volume()));
            sonificationPreferencesRepository.save(accessibleChartController == null
                    ? sonificationPreferences : accessibleChartController.preferences());
        } catch (RuntimeException error) {
            if (status != null) status.setText("UI 설정 저장 실패: " + error.getMessage());
        }
    }

    private void announce(String text, SpeechPriority priority, String key) {
        announce(text, priority, key, SpeechMergePolicy.KEEP_FIRST);
    }

    /** 사용자가 직접 누른 듣기 동작은 TTS가 꺼져 있어도 이유를 알려 준다. */
    private void requestSpeech(String text, String key) {
        if (!speechEnabled || speechQueue.isClosed()) {
            status.setText("음성 안내가 꺼져 있습니다. 설정에서 화면 읽기(TTS)를 켜주세요.");
            play(SoundCue.WARNING);
            return;
        }
        announce(text, SpeechPriority.USER_REQUEST, key);
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
        if (persistenceDelay != null) persistenceDelay.stop();
        saveLocalState();
        if (accessibleChartController != null) accessibleChartController.close();
        marketApplication.close();
        sonificationPort.close();
        speechQueue.close();
        soundPort.close();
        secretStore.close();
    }
    public static void main(String[] args) { launch(args); }
}
