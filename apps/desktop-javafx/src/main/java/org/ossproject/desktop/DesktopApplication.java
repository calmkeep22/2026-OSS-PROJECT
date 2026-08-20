package org.ossproject.desktop;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.css.PseudoClass;
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
import javafx.scene.input.KeyEvent;
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
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.desktop.composition.DesktopServices;
import org.ossproject.finance.model.*;
import org.ossproject.desktop.chart.AccessibleChartController;
import org.ossproject.desktop.chart.AccessibleChartView;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.desktop.presentation.Formatters;
import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.navigation.SidebarNavigationModel;
import org.ossproject.desktop.controller.DesktopScreenController;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.secret.SecretStore;
import org.ossproject.desktop.viewmodel.DesktopSession;
import org.ossproject.desktop.viewmodel.StockSearchItem;
import org.ossproject.desktop.viewmodel.StockSearchViewModel;
import org.ossproject.desktop.viewmodel.ConnectionViewModel;
import org.ossproject.desktop.viewmodel.WatchlistViewModel;
import org.ossproject.desktop.viewmodel.StockDetailViewModel;
import org.ossproject.desktop.viewmodel.StockSelection;
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
    private static final PseudoClass EXPANDED = PseudoClass.getPseudoClass("expanded");
    private static final PseudoClass CURRENT_GROUP = PseudoClass.getPseudoClass("current-group");

    private final TradingUseCase tradingUseCase;
    private final MarketApplicationPort marketApplication;
    private final CandleQueryPort candleAdapter;
    /** 시세 공급원 설명. 상태 표시줄에 그대로 보여 준다. */
    private final String marketDataSource;
    private final SpeechPort speechPort;
    private final SpeechQueue speechQueue;
    private final SoundPort soundPort;
    private final SonificationPort sonificationPort;
    private final SecretStore secretStore;
    private AccessibleChartController accessibleChartController;
    private final Label status = new Label("준비됨");
    private final Label lastDataTime = new Label("마지막 시세 --:--:--");
    private final StackPane screenHost = new StackPane();
    private final Map<Screen, Button> navigationButtons = new EnumMap<>(Screen.class);
    private final Map<Screen.NavigationGroup, Button> navigationGroupButtons =
            new EnumMap<>(Screen.NavigationGroup.class);
    private final Map<Screen.NavigationGroup, VBox> navigationGroupContents =
            new EnumMap<>(Screen.NavigationGroup.class);
    private final SidebarNavigationModel sidebarNavigation = new SidebarNavigationModel();
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
        this.candleAdapter = services.candles();
        this.marketDataSource = services.marketDataSource();
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
        configureScreens();
        speechQueue.addListener(new SpeechListener() {
            @Override public void onStarted(SpeechRequest request) {
                setChartSpeechActive(true);
            }

            @Override public void onCompleted(SpeechRequest request) {
                setChartSpeechActive(false);
            }

            @Override public void onFailed(SpeechRequest request, RuntimeException error) {
                setChartSpeechActive(false);
                Platform.runLater(() -> {
                    status.setText("음성 출력 실패: " + error.getMessage());
                    play(SoundCue.ERROR);
                });
            }

            @Override public void onInterrupted(SpeechRequest request) {
                setChartSpeechActive(false);
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
        Label mode = new Label(marketDataSource.startsWith("키움") ? "키움 모의투자" : "미연결");
        mode.getStyleClass().add("mode-badge");
        mode.setAccessibleText("실행 모드. " + marketDataSource);
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
        navigationButtons.clear();
        navigationGroupButtons.clear();
        navigationGroupContents.clear();
        VBox nav = new VBox(6);
        for (Screen.NavigationGroup group : Screen.NavigationGroup.values()) {
            Button groupButton = new Button();
            groupButton.getStyleClass().add("nav-group-button");
            groupButton.setMaxWidth(Double.MAX_VALUE);
            groupButton.setAccessibleHelp("Enter 또는 Space로 하위 메뉴를 열고 닫습니다. 오른쪽 방향키로 열고 왼쪽 방향키로 닫습니다.");
            groupButton.setOnAction(event -> toggleNavigationGroup(group));
            groupButton.setOnKeyPressed(event -> handleNavigationGroupKey(event, group));

            VBox children = new VBox(3);
            children.getStyleClass().add("nav-group-children");
            for (Screen screen : sidebarNavigation.children(group)) {
                Button button = new Button(screen.label());
                button.getStyleClass().addAll("nav-button", "nav-child-button");
                button.setMaxWidth(Double.MAX_VALUE);
                button.setAccessibleText(group.label() + " 메뉴, " + screen.label() + " 화면 열기");
                button.setAccessibleHelp("Enter 또는 Space로 화면을 엽니다. 왼쪽 방향키로 상위 메뉴로 이동합니다.");
                button.setOnAction(event -> openNavigationScreen(screen));
                button.setOnKeyPressed(event -> {
                    if (event.getCode() == KeyCode.LEFT) {
                        groupButton.requestFocus();
                        event.consume();
                    }
                });
                navigationButtons.put(screen, button);
                children.getChildren().add(button);
            }

            navigationGroupButtons.put(group, groupButton);
            navigationGroupContents.put(group, children);
            nav.getChildren().addAll(groupButton, children);
        }
        updateNavigationGroups(null);
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

    private void toggleNavigationGroup(Screen.NavigationGroup group) {
        sidebarNavigation.toggle(group);
        updateNavigationGroups(group);
    }

    private void handleNavigationGroupKey(KeyEvent event, Screen.NavigationGroup group) {
        if (event.getCode() == KeyCode.RIGHT) {
            if (sidebarNavigation.isExpanded(group)) {
                sidebarNavigation.children(group).stream().findFirst()
                        .map(navigationButtons::get)
                        .ifPresent(Button::requestFocus);
            } else {
                sidebarNavigation.expand(group);
                updateNavigationGroups(group);
            }
            event.consume();
        } else if (event.getCode() == KeyCode.LEFT && sidebarNavigation.isExpanded(group)) {
            sidebarNavigation.collapse(group);
            updateNavigationGroups(group);
            event.consume();
        }
    }

    private void revealNavigationGroup(Screen screen) {
        sidebarNavigation.reveal(screen);
        updateNavigationGroups(null);
    }

    private void updateNavigationGroups(Screen.NavigationGroup changedGroup) {
        Screen.NavigationGroup activeGroup = screenController == null
                ? Screen.NavigationGroup.OVERVIEW
                : screenController.currentScreen()
                        .map(Screen::navigationGroup)
                        .orElse(Screen.NavigationGroup.OVERVIEW);
        for (Screen.NavigationGroup group : Screen.NavigationGroup.values()) {
            boolean expanded = sidebarNavigation.isExpanded(group);
            Button button = navigationGroupButtons.get(group);
            VBox children = navigationGroupContents.get(group);
            if (button == null || children == null) continue;
            button.setText((expanded ? "▾  " : "▸  ") + group.label());
            button.setAccessibleText(group.label() + " 메뉴, " + (expanded ? "펼쳐짐" : "접힘"));
            button.pseudoClassStateChanged(EXPANDED, expanded);
            button.pseudoClassStateChanged(CURRENT_GROUP, group == activeGroup);
            children.setVisible(expanded);
            children.setManaged(expanded);
        }
        if (changedGroup != null) {
            Button changedButton = navigationGroupButtons.get(changedGroup);
            if (changedButton != null) {
                changedButton.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
            }
        }
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

        // 상단 표시는 실제 상태를 따른다. 연결되어 있는데 미연결로 보이거나 그 반대면,
        // 화면을 볼 수 없는 사용자는 지금 값이 실제 시세인지 판단할 근거를 잃는다.
        boolean live = marketDataSource.startsWith("키움");
        Label market = new Label(live ? "조회 시세 · " + marketDataSource : marketDataSource);
        market.getStyleClass().addAll("status-chip", "mode-badge");
        market.setAccessibleText("시세 출처. " + marketDataSource);
        Button connection = new Button(live ? "키움 API · 연결됨" : "키움 API · 미연결");
        connection.getStyleClass().add("connection-button");
        connection.setOnAction(event -> navigate(Screen.CONNECTION));

        Button alerts = new Button();
        alerts.setOnAction(event -> navigate(Screen.NOTIFICATIONS));
        Runnable refreshAlertCount = () -> {
            int count = session.notifications().size();
            alerts.setText("알림 " + count);
            alerts.setAccessibleText(count == 0 ? "알림 없음" : "알림 " + count + "건");
        };
        session.notifications().addListener(
                (javafx.collections.ListChangeListener<String>) change -> refreshAlertCount.run());
        refreshAlertCount.run();

        // 계좌번호는 증권사에서 받아야 알 수 있다. 임의의 번호를 보여 주지 않는다.
        Button account = new Button("계좌");
        account.setAccessibleText("계좌 화면 열기");
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
        Screen.NavigationGroup group = screenController == null
                ? Screen.NavigationGroup.OVERVIEW
                : screenController.currentScreen()
                        .map(Screen::navigationGroup)
                        .orElse(Screen.NavigationGroup.OVERVIEW);
        Button groupButton = navigationGroupButtons.get(group);
        if (groupButton != null) groupButton.requestFocus();
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
            // 후보가 하나뿐이거나 종목코드가 정확히 일치할 때만 바로 연다. 종목명이 같아도
            // "한화"처럼 다른 종목의 앞부분과 겹치면, 앱이 대신 골라 버리는 대신 목록을
            // 보여 준다. 화면을 볼 수 없는 사용자가 다른 후보를 놓치지 않게 하기 위해서다.
            StockSearchItem unambiguous = result.count() == 1
                    ? stockSearchViewModel.items().get(0)
                    : stockSearchViewModel.exactSymbolMatch(query).orElse(null);
            if (unambiguous != null) {
                stockSearchViewModel.setPreferredSymbol(null);
                stockSearchViewModel.select(unambiguous);
                navigate(Screen.STOCK_DETAIL);
                return;
            }

            var exact = stockSearchViewModel.exactMatch(query);
            stockSearchViewModel.setPreferredSymbol(exact.map(StockSearchItem::symbol).orElse(null));
            screenController.invalidate(Screen.SEARCH);
            navigate(Screen.SEARCH);
            if (result.count() == 0) {
                status.setText(query + " 검색 결과가 없습니다.");
            } else if (exact.isPresent()) {
                status.setText(query + " 검색 결과 " + result.count() + "건입니다. "
                        + exact.get().name() + " " + exact.get().symbol()
                        + " 이(가) 선택되어 있습니다. Enter 키를 누르면 상세 화면을 엽니다.");
            } else {
                status.setText(query + " 검색 결과 " + result.count() + "건에서 종목을 선택해주세요.");
            }
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
        if (screen == Screen.STOCK_DETAIL || screen == Screen.TRADING) screenController.invalidate(screen);
        screenController.show(screen);
    }

    private javafx.scene.Node createAccessibleChartScreen() {
        if (stockDetailViewModel.hasCurrentChartData()) {
            try {
                return rebuildAccessibleChart().root();
            } catch (RuntimeException error) {
                return accessibleChartError("청각 차트를 준비하지 못했습니다: " + error.getMessage());
            }
        }

        StockSelection requested = session.selectedStock();
        Label loading = new Label(requested.name() + " 청각 차트 데이터를 불러오는 중입니다.");
        loading.setAccessibleText(loading.getText());
        StackPane placeholder = new StackPane(loading);
        placeholder.getStyleClass().add("screen-content");
        placeholder.setPadding(new Insets(32));

        stockDetailViewModel.loadInitial().whenComplete((data, failure) -> {
            if (screenController.currentScreen().orElse(null) != Screen.RADIO) return;
            if (failure != null) {
                loading.setText("청각 차트 데이터를 불러오지 못했습니다. 연결 상태를 확인해주세요.");
                loading.setAccessibleText(loading.getText());
                status.setText(loading.getText());
                play(SoundCue.ERROR);
                return;
            }
            if (!requested.securityId().equals(session.selectedStock().securityId())) return;
            try {
                AccessibleChartView view = rebuildAccessibleChart();
                placeholder.getChildren().setAll(view.root());
                screenController.focusContent();
                status.setText(data.detail().name() + " 청각 차트를 준비했습니다.");
            } catch (RuntimeException error) {
                loading.setText("청각 차트를 준비하지 못했습니다: " + error.getMessage());
                loading.setAccessibleText(loading.getText());
                status.setText(loading.getText());
                play(SoundCue.ERROR);
            }
        });
        return placeholder;
    }

    /**
     * 청각 차트를 열지 못했을 때 대신 보여 줄 화면.
     *
     * <p>실패 사유를 텍스트로 남기고 다시 시도할 수단을 함께 준다. 화면을 볼 수 없는
     * 사용자는 실패했다는 사실만으로는 다음에 무엇을 할 수 있는지 알 수 없다.
     */
    private javafx.scene.Node accessibleChartError(String message) {
        Label description = new Label(message);
        description.setWrapText(true);
        description.setAccessibleText(message);
        Button retry = new Button("다시 시도");
        retry.setAccessibleText("다시 시도. 청각 차트를 다시 준비합니다.");
        retry.setOnAction(event -> retryAccessibleChart());
        VBox error = new VBox(18, description, retry);
        error.getStyleClass().add("screen-content");
        error.setPadding(new Insets(32));
        error.setAccessibleText(message);
        status.setText(message);
        play(SoundCue.ERROR);
        return error;
    }

    /** 실패한 청각 차트 화면을 버리고 처음부터 다시 만든다. */
    private void retryAccessibleChart() {
        status.setText("청각 차트를 다시 준비하고 있습니다.");
        screenController.invalidate(Screen.RADIO);
        screenController.show(Screen.RADIO);
    }

    private AccessibleChartView rebuildAccessibleChart() {
        if (accessibleChartController != null) {
            sonificationPreferences = accessibleChartController.preferences();
            accessibleChartController.close();
        }
        StockDetailViewModel.ChartRange range = stockDetailViewModel.selectedChartRange();
        List<Candle> candles = stockDetailViewModel.selectedCandles();
        String seriesDescription = range.label() + "봉 " + candles.size() + "개 종가";
        accessibleChartController = new AccessibleChartController(
                session.selectedStock().securityId(), stockDetailViewModel.detail(), candles,
                seriesDescription, marketApplication, sonificationPort,
                this::requestSpeech, status::setText);
        accessibleChartController.applyPreferences(sonificationPreferences);
        accessibleChartController.setPreferencesListener(preferences -> {
            sonificationPreferences = preferences;
            scheduleStateSave();
        });
        return new AccessibleChartView(accessibleChartController);
    }

    /**
     * 아직 증권사와 연동하지 않은 화면에 대신 보여 줄 안내.
     *
     * <p>값을 지어내 채우지 않는다. 화면을 볼 수 없는 사용자는 표에 있는 숫자가 실제 시장
     * 값인지 확인할 방법이 없으므로, 없는 데이터는 없다고 말하는 편이 안전하다.
     *
     * @param what 어떤 데이터인지
     * @param tr   연동에 사용할 키움 TR. 후속 작업을 알아볼 수 있게 함께 적는다
     */
    private javafx.scene.Node notConnectedPanel(String what, String tr) {
        Label heading = new Label(what + " 데이터는 아직 연동되지 않았습니다.");
        heading.getStyleClass().add("safety-note");
        heading.setWrapText(true);
        Label detail = new Label("실제 값을 받아오기 전까지 임의의 숫자를 표시하지 않습니다. "
                + "연동 예정 항목: " + tr);
        detail.setWrapText(true);
        VBox panel = new VBox(10, heading, detail);
        panel.setPadding(new Insets(20));
        panel.setAccessibleText(what + " 데이터는 아직 연동되지 않았습니다. "
                + "실제 값을 받아오기 전까지 임의의 숫자를 표시하지 않습니다.");
        return panel;
    }

    /** 음성이 나가는 동안 청각 차트 음량을 낮춘다. 차트를 못 연 상태면 할 일이 없다. */
    private void setChartSpeechActive(boolean active) {
        AccessibleChartController controller = accessibleChartController;
        if (controller != null) controller.setSpeechActive(active);
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
            if (old == Screen.RADIO && screen != Screen.RADIO
                    && accessibleChartController != null) {
                accessibleChartController.stopLive();
                accessibleChartController.stop();
            }
            if (screen == null) return;
            revealNavigationGroup(screen);
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
        screenController.register(Screen.RADIO, this::createAccessibleChartScreen);
        screenController.registerPreservingState(Screen.SETTINGS, this::createSettingsScreen);
    }

    private ScrollPane createDashboard() {
        Account snapshot = tradingUseCase.account();
        Label title = heading("안녕하세요. 오늘의 투자 현황입니다");
        Label description = new Label("모의투자 계좌 " + snapshot.maskedAccountNo());
        description.getStyleClass().add("muted-text");
        VBox intro = new VBox(4, title, description);
        Button order = primaryButton("주문하기", () -> openOrder(OrderSide.BUY));
        Button listen = new Button("화면 요약 듣기");
        // 읽어 주는 문장도 실제 계좌 값에서 만든다. 화면과 음성이 다르면 안 된다.
        listen.setOnAction(event -> requestSpeech(
                "총 자산 " + Formatters.won(snapshot.totalAssets())
                        + ", 평가손익은 " + signedWon(snapshot.totalProfitLoss())
                        + " 입니다. 보유 종목은 " + snapshot.positions().size() + "종목입니다.",
                "dashboard-summary"));
        Region titleSpacer = new Region(); HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, intro, titleSpacer, listen, order); header.setAlignment(Pos.CENTER_LEFT);

        FlowPane assets = wrappingRow(14,
                summaryCard("총 자산", Formatters.won(snapshot.totalAssets()),
                        "예수금 포함", "neutral"),
                summaryCard("평가손익", signedWon(snapshot.totalProfitLoss()),
                        "평가금액 " + Formatters.won(snapshot.totalMarketValue()),
                        snapshot.totalProfitLoss().signum() >= 0 ? "positive" : "negative"),
                summaryCard("주문 가능 금액", Formatters.won(snapshot.balance().available()),
                        "예수금 " + Formatters.won(snapshot.balance().cash()), "neutral"));

        TableView<ObservableList<String>> holdings = textTable("홈 보유종목 요약",
                snapshot.positions().stream().map(position -> row(
                        position.name(),
                        position.quantity() + "주",
                        Formatters.won(position.currentPrice()),
                        position.profitLossRate().toPlainString() + "%")).toList(),
                "종목", "수량", "현재가", "수익률");
        holdings.setPrefHeight(210);
        holdings.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(holdings, 0); });
        holdings.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(holdings, 0); });

        VBox left = card("보유종목", holdings, linkButton("계좌 전체 보기", Screen.ACCOUNT));
        // 지수와 거래대금 순위는 조회 TR 을 연동해야 채울 수 있다. 예시 숫자를 넣지 않는다.
        VBox right = card("오늘 시장",
                notConnectedPanel("지수와 거래대금 순위",
                        "ka20003 전업종지수, ka10032 거래대금상위"),
                linkButton("랭킹 전체 보기", Screen.SCANNER));
        SplitPane center = new SplitPane(left, right); center.setDividerPositions(0.5);
        left.setMinWidth(0); right.setMinWidth(0);

        // 알림은 사용자의 주문·체결에서 쌓인다. 세션이 비어 있으면 비어 있는 대로 보여 준다.
        ListView<String> activity = new ListView<>(session.notifications());
        activity.setAccessibleText("최근 주문과 알림"); activity.setPrefHeight(145);
        activity.setPlaceholder(new Label("최근 주문과 알림이 아직 없습니다."));
        VBox activityCard = card("최근 주문 · 알림", activity);

        VBox body = new VBox(20, header, assets, center, activityCard);
        return scrollPage("홈 대시보드", body);
    }

    private ScrollPane createConnectionScreen() {
        return new ConnectionScreenView(connectionViewModel, status::setText).create();
    }

    private ScrollPane createAccountScreen() {
        Account snapshot = tradingUseCase.account();
        Label title = heading("계좌");
        // 계좌번호는 접근 토큰에 연결된 것을 그대로 보여 준다. 목록을 지어내지 않는다.
        Label accountNo = new Label("모의계좌 " + snapshot.maskedAccountNo());
        accountNo.getStyleClass().add("status-chip");
        accountNo.setAccessibleText("조회 중인 계좌. 모의계좌 " + snapshot.maskedAccountNo());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, accountNo); header.setAlignment(Pos.CENTER_LEFT);

        // 값은 모의주문 엔진이 들고 있는 실제 계좌 상태에서 읽는다. 화면이 따로 계산하거나
        // 예시 숫자를 적어 두지 않는다.
        FlowPane metrics = wrappingRow(14,
                summaryCard("총 평가자산", Formatters.won(snapshot.totalAssets()),
                        "보유 " + snapshot.positions().size() + "종목 + 예수금", "neutral"),
                summaryCard("평가손익", signedWon(snapshot.totalProfitLoss()),
                        "평가금액 " + Formatters.won(snapshot.totalMarketValue()),
                        snapshot.totalProfitLoss().signum() >= 0 ? "positive" : "negative"),
                summaryCard("예수금", Formatters.won(snapshot.balance().cash()),
                        "주문 가능 " + Formatters.won(snapshot.balance().available()), "neutral"));

        TableView<ObservableList<String>> holdings = textTable("보유종목 표",
                snapshot.positions().stream().map(position -> row(
                        position.name(),
                        position.quantity() + "",
                        Formatters.won(position.averagePrice()),
                        Formatters.won(position.currentPrice()),
                        Formatters.won(position.marketValue()),
                        signedWon(position.profitLoss()),
                        position.profitLossRate().toPlainString() + "%")).toList(),
                "종목", "수량", "평균단가", "현재가", "평가금액", "손익", "수익률");
        holdings.setPrefHeight(300);
        holdings.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelectedStock(holdings, 0); });
        holdings.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelectedStock(holdings, 0); });
        Button holdingDetail = new Button("선택 종목 상세"); holdingDetail.setOnAction(event -> openSelectedStock(holdings, 0));
        Button holdingBuy = primaryButton("선택 종목 매수", () -> navigateForSelectedStock(holdings, 0, OrderSide.BUY));
        Button holdingSell = new Button("선택 종목 매도"); holdingSell.setOnAction(event -> navigateForSelectedStock(holdings, 0, OrderSide.SELL));
        VBox holdingsPanel = new VBox(10, holdings, wrappingRow(8, holdingDetail, holdingBuy, holdingSell));
        holdingsPanel.setPadding(new Insets(10));

        // 예수금과 주문 가능 금액은 모의주문 엔진이 들고 있는 잔고에서 읽는다.
        // D+1·D+2 정산과 출금 가능 금액은 별도 TR 이라 연동 전까지 표시하지 않는다.
        VBox cash = new VBox(12,
                informationRow("예수금", Formatters.won(snapshot.balance().cash())),
                informationRow("주문 대기 금액", Formatters.won(snapshot.balance().locked())),
                informationRow("주문 가능 금액", Formatters.won(snapshot.balance().available())),
                notConnectedPanel("D+1·D+2 예수금과 출금 가능 금액",
                        "kt00001 예수금상세현황요청, kt00010 주문인출가능금액"));
        cash.setPadding(new Insets(20));

        TableView<ObservableList<String>> open = orderStatusTable(true);
        TableView<ObservableList<String>> fills = orderStatusTable(false);
        TableView<ObservableList<String>> history = textTable("주문내역",
                tradingUseCase.orders().stream().map(order -> row(
                        orderTime(order), order.name(), order.side().displayName(),
                        order.limitPrice() == null ? "시장가" : Formatters.won(order.limitPrice()),
                        Long.toString(order.quantity()), order.status().displayName())).toList(),
                "시간", "종목", "구분", "주문가", "수량", "상태");
        history.setPlaceholder(new Label("주문 내역이 없습니다."));
        // 기간별 누적 수익률은 증권사에서 받아야 한다. 현재 보유분의 평가손익만 실제 값으로
        // 보여 주고, 기간 수익률은 연동 전까지 표시하지 않는다.
        VBox profit = new VBox(16,
                informationRow("보유 종목 평가손익", signedWon(snapshot.totalProfitLoss())),
                informationRow("매입금액", Formatters.won(snapshot.positions().stream()
                        .map(Position::costBasis).reduce(BigDecimal.ZERO, BigDecimal::add))),
                informationRow("평가금액", Formatters.won(snapshot.totalMarketValue())),
                notConnectedPanel("기간별 누적 수익률과 실현손익",
                        "ka10074 일자별실현손익, ka10085 계좌수익률, kt00016 일별계좌수익률상세"));
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
                // 호가는 ka10004 로 따로 받아야 한다. 현재가에서 계산해 보여 주면 시장에 없는
                // 가격으로 주문할 수 있으므로 연동 전까지 표시하지 않는다.
                informationRow("시가", stockDetailViewModel.formatPrice(selectedDetail.open())),
                informationRow("고가 / 저가",
                        stockDetailViewModel.formatPrice(selectedDetail.high()) + " / "
                                + stockDetailViewModel.formatPrice(selectedDetail.low())));
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
                miniMetric("저가", stockDetailViewModel.formatPrice(detail.low())),
                // 시가총액과 외국인 소진률은 ka10001 이 함께 주지만 아직 도메인 모델에 담지
                // 않았다. 값을 지어내지 않고 항목 자체를 빼 둔다.
                miniMetric("거래량", String.format("%,d", detail.volume())));

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

        // 호가·체결·수급·기업정보는 아직 연동하지 않았다. 예전에는 현재가에 임의의 값을
        // 더해 호가를 만들어 보여 주고, 그 호가를 주문 가격으로 넣을 수도 있었다. 시장에
        // 없는 가격으로 주문이 나갈 수 있어 표시 자체를 없앤다.
        javafx.scene.Node orderBook = notConnectedPanel("호가", "ka10004 주식호가요청");
        javafx.scene.Node trades = notConnectedPanel("체결", "ka10003 체결정보요청");
        javafx.scene.Node supply = notConnectedPanel("투자자 수급",
                "ka10059 종목별투자자기관별, ka10008 외국인 종목별 매매동향");
        javafx.scene.Node info = notConnectedPanel("기업정보",
                "ka10001 주식기본정보요청의 PER·EPS·PBR·시가총액");

        TabPane tabs = new TabPane(tab("차트", chart), tab("호가", orderBook), tab("체결", trades),
                tab("수급", supply), tab("거래원", createBrokerAnalysisPanel()),
                tab("프로그램", createStockProgramPanel()), tab("기업정보", info));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(440);
        VBox body = new VBox(16, titleRow, wrappingRow(12, price, listen), metrics, tabs);
        return scrollPage("종목 상세 " + detail.name(), body);
    }

    private ScrollPane createMarketScreen() {
        Label title = heading("국내 시장");
        // 지수·순위·업종·테마·ETF·ELW·금현물은 모두 조회 TR 이 따로 있다. 연동 전까지는
        // 예시 숫자를 채우지 않는다. 시장 화면의 숫자는 사용자가 종목을 고르는 근거가 되므로
        // 지어낸 값이 특히 위험하다.
        javafx.scene.Node domestic = notConnectedPanel("국내 지수와 순위",
                "ka20003 전업종지수, ka10030 당일거래량상위, ka10032 거래대금상위");
        javafx.scene.Node sectors = notConnectedPanel("업종 지수", "ka20001 업종현재가, ka20002 업종별주가");
        javafx.scene.Node themes = notConnectedPanel("테마", "ka90001 테마그룹별, ka90002 테마구성종목");
        javafx.scene.Node etf = notConnectedPanel("ETF", "ka40004 ETF전체시세, ka40002 ETF종목정보");
        javafx.scene.Node elw = notConnectedPanel("ELW", "ka30005 ELW조건검색, ka30012 ELW종목상세정보");
        javafx.scene.Node gold = notConnectedPanel("금현물", "ka50100 금현물 시세정보, ka50101 금현물 호가");
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

    /**
     * 조건검색 화면.
     *
     * <p>조건식은 사용자가 키움 HTS 에서 만들어 둔 것을 불러와야 한다. 예전에는 조건식 이름과
     * 검색 결과를 앱이 지어내 보여 주었는데, 사용자가 만들지도 않은 조건식이 자기 것처럼
     * 보이면 실제 조건검색과 구분할 수 없다.
     */
    private ScrollPane createConditionScreen() {
        Label title = heading("조건검색");
        return scrollPage("조건검색", new VBox(20, title, notConnectedPanel("조건검색",
                "ka10171 조건검색 목록조회, ka10172 조건검색 요청 일반, ka10173 조건검색 실시간")));
    }

    private ScrollPane createSupplyScreen() {
        Label title = heading("수급 분석");
        javafx.scene.Node investors = notConnectedPanel("외국인 · 기관 수급",
                "ka10008 외국인 종목별 매매동향, ka10059 종목별투자자기관별, ka10131 기관외국인연속매매현황");
        javafx.scene.Node program = notConnectedPanel("프로그램매매",
                "ka90005 프로그램매매추이 시간대별, ka90006 프로그램매매차익잔고추이");
        javafx.scene.Node shortSelling = notConnectedPanel("공매도", "ka10014 공매도추이요청");
        javafx.scene.Node lending = notConnectedPanel("대차거래",
                "ka10068 대차거래추이, ka10069 대차거래상위10종목");
        TabPane tabs = new TabPane(tab("외국인 · 기관", investors),
                tab("프로그램매매", program), tab("공매도", shortSelling), tab("대차거래", lending),
                tab("거래원", createBrokerAnalysisPanel()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(430);
        return scrollPage("수급 분석", new VBox(20, title, tabs));
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

    // 미국주식은 국내주식과 TR 체계가 완전히 다르다(usa*, ust*). 어댑터에 아직 구현하지
    // 않았으므로 화면에 값을 지어내지 않는다. 특히 주문 화면은 실제로 주문을 보낼 수 없는데
    // 보낼 수 있는 것처럼 보이면 안 된다.

    private VBox createUsHomePanel() {
        VBox panel = new VBox(18, notConnectedPanel("미국 지수와 랭킹",
                "usa10102 미국지수 리스트, usa20530 거래량상위, usa20540 거래대금상위"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsStockPanel() {
        VBox panel = new VBox(14, notConnectedPanel("미국 종목 상세",
                "usa20100 현재가 종목정보, usa20101 10호가, usa06012 일 차트"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsScannerPanel() {
        VBox panel = new VBox(16, notConnectedPanel("미국주식 스캐너",
                "usa20510 기간별 등락률상위, usa20530 거래량상위, usa24100 신고가/신저가"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsConditionPanel() {
        VBox panel = new VBox(16, notConnectedPanel("미국 조건검색",
                "usa20280 조건검색 목록조회, usa20281 조건검색 요청 일반"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsAccountPanel() {
        VBox panel = new VBox(16, notConnectedPanel("미국주식 계좌",
                "ust21070 원장잔고확인, ust21110 해외주식 예수금, ust21120 통화별 예수금"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsWatchlistPanel() {
        // 관심종목 자체는 사용자의 기록이라 그대로 보여 준다. 다만 미국주식 주문은 아직
        // 연동하지 않았으므로 주문할 수 있는 것처럼 보이게 하지 않는다.
        return new WatchlistScreenView(watchlistViewModel, this::openWatchlistStock,
                () -> startWatchlistSearch("미국"), status::setText)
                .createUsPanel(selected -> status.setText(
                        "미국주식 주문은 아직 연동되지 않았습니다. 연동 예정: ust20000 매수, ust20001 매도"));
    }

    private VBox createUsOrderPanel() {
        VBox panel = new VBox(notConnectedPanel("미국주식 주문",
                "ust20000 매수, ust20001 매도, ust20002 정정, ust20003 취소, ust21050 원장 미체결"));
        panel.setPadding(new Insets(18));
        return panel;
    }

    private VBox createUsResearchPanel() {
        VBox panel = new VBox(14, notConnectedPanel("미국주식 리서치", "usa24300 미국주식 리서치"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createFxPanel() {
        VBox panel = new VBox(16, notConnectedPanel("환전",
                "ust31301 환율조회, ust31300 환전 예상금액, ust31302 환전신청"));
        panel.setPadding(new Insets(12));
        return panel;
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
        VBox panel = new VBox(14, notConnectedPanel("거래원 분석",
                "ka10040 당일주요거래원, ka10042 순매수거래원순위"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createStockProgramPanel() {
        VBox panel = new VBox(12, notConnectedPanel("프로그램매매",
                "ka90004 종목별프로그램매매현황, ka90008 종목시간별프로그램매매추이"));
        panel.setPadding(new Insets(12));
        return panel;
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

    /**
     * 신용거래 안내.
     *
     * <p>신용 한도와 이율은 계좌마다 다르고 증권사에서 받아야 하는 값이다. 원금 초과 손실이
     * 가능한 거래라 예시 숫자를 보여 주는 것 자체가 위험하다.
     */
    private VBox createCreditTradingPanel() {
        Label warning = stateBanner(
                "신용거래는 원금 초과 손실 가능성이 있습니다. 한도와 이율은 계좌마다 다릅니다.", "warning");
        VBox panel = new VBox(14, warning, notConnectedPanel("신용거래",
                "kt20016 신용융자 가능종목, kt00012 신용보증금율별 주문가능수량, kt10006 신용 매수주문"));
        panel.setPadding(new Insets(14));
        return panel;
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

    /**
     * 주문 상태 표.
     *
     * <p>모의주문 엔진이 들고 있는 실제 주문에서 만든다. 예전에는 예시 주문을 적어 두어,
     * 사용자가 낸 주문과 앱이 넣어 둔 예시를 구분할 수 없었다.
     */
    private TableView<ObservableList<String>> orderStatusTable(boolean open) {
        List<Order> orders = open
                ? tradingUseCase.openOrders()
                : tradingUseCase.orders().stream().filter(order -> order.status().isTerminal()).toList();
        // 주문번호를 함께 보여 준다. 취소·정정은 이 번호로 원주문을 지정하고, 사용자도
        // 증권사 화면과 대조할 수 있어야 한다.
        List<String[]> rows = orders.stream().map(order -> row(
                order.orderId(),
                orderTime(order),
                order.name(),
                order.side().displayName(),
                order.limitPrice() == null ? "시장가" : Formatters.won(order.limitPrice()),
                Long.toString(order.quantity()),
                Long.toString(order.filledQuantity()),
                Long.toString(order.remainingQuantity()),
                order.status().displayName())).toList();
        TableView<ObservableList<String>> table = textTable(open ? "미체결 주문" : "체결·종료 주문", rows,
                "주문번호", "시간", "종목", "구분", "주문가", "수량", "체결", "잔여", "상태");
        table.setPlaceholder(new Label(open
                ? "미체결 주문이 없습니다." : "체결되었거나 종료된 주문이 없습니다."));
        return table;
    }

    /** 주문 접수 시각을 화면 표기로 바꾼다. */
    private static String orderTime(Order order) {
        return java.time.LocalDateTime.ofInstant(order.createdAt(), java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"));
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
            // 이 표는 화면 상태 구성요소를 확인하려는 견본이다. 실제 종목명을 쓰면 시세로
            // 오해할 수 있어, 값이 아니라 자리라는 것이 드러나는 문자열을 쓴다.
            TableView<ObservableList<String>> table = textTable("정상 상태 표 견본",
                    List.of(row("종목 A", "가격 1", "등락률 1"), row("종목 B", "가격 2", "등락률 2")),
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

    /**
     * 주문 정정 안내.
     *
     * <p>정정은 별도 TR(kt10002)이며 아직 연동하지 않았다. 예전에는 표의 글자만 바꿔 정정된
     * 것처럼 보이게 했는데, 실제로는 원주문이 그대로 살아 있었다. 화면을 볼 수 없는 사용자는
     * 정정되었다고 안내받고도 옛 가격으로 체결될 수 있었다.
     *
     * <p>연동 전까지는 취소 후 재주문으로 안내한다. 두 동작 모두 실제로 증권사에 전달된다.
     */
    private void showAmendOrderDialog(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInformation("주문을 선택하세요", "정정할 미체결 주문을 먼저 선택해주세요.");
            return;
        }
        String message = "주문 정정은 아직 연동되지 않았습니다. 연동 예정: kt10002 주식 정정주문\n\n"
                + "지금은 선택 주문을 취소한 뒤 새로 주문해주세요. 취소와 신규 주문은 실제로 "
                + "키움 모의투자 계좌에 전달됩니다.\n\n"
                + "선택한 주문번호: " + selected.get(0) + " · " + selected.get(2);
        status.setText("주문 정정은 아직 연동되지 않았습니다. 취소 후 재주문해주세요.");
        showInformation("주문 정정을 사용할 수 없습니다", message);
    }

    /**
     * 선택한 주문을 취소한다.
     *
     * <p>예전에는 표의 글자만 바꿔 취소한 것처럼 보이게 했다. 실제로는 취소되지 않았으므로,
     * 화면을 볼 수 없는 사용자는 취소되었다고 안내받고도 주문이 살아 있는 상태였다.
     * 이제 증권사에 취소를 보내고 결과를 다시 읽어 온다.
     */
    private void cancelSelectedOrder(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showInformation("주문을 선택하세요", "취소할 미체결 주문을 먼저 선택해주세요.");
            return;
        }
        String orderId = selected.get(0);
        String name = selected.get(2);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("주문 취소 재확인");
        confirmation.setHeaderText(name + " 잔여 " + selected.get(7) + "주를 취소하시겠습니까?");
        confirmation.setContentText("주문번호: " + orderId + "\n원주문 가격: " + selected.get(4)
                + "\n\n키움 모의투자 계좌로 취소 요청을 보냅니다.");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            try {
                Order cancelled = tradingUseCase.cancel(orderId);
                String message = name + " 주문번호 " + orderId + " 을(를) 취소했습니다. 상태 "
                        + cancelled.status().displayName();
                status.setText(message);
                addNotification("주문", message);
                announce(message, SpeechPriority.ORDER, "order-cancel-" + orderId);
                play(SoundCue.SUCCESS);
                screenController.invalidate(Screen.TRADING);
                screenController.invalidate(Screen.ACCOUNT);
            } catch (RuntimeException failure) {
                String reason = failure.getMessage() == null || failure.getMessage().isBlank()
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                // 취소 실패를 성공처럼 보이게 두면 안 된다. 주문은 아직 살아 있을 수 있다.
                status.setText("주문 취소에 실패했습니다. " + reason);
                addNotification("주문", "주문번호 " + orderId + " 취소에 실패했습니다. " + reason);
                announce("주문 취소에 실패했습니다. " + reason, SpeechPriority.CRITICAL,
                        "order-cancel-failed-" + orderId);
                play(SoundCue.ERROR);
                showInformation("주문을 취소하지 못했습니다", reason
                        + "\n\n주문이 아직 남아 있을 수 있습니다. 미체결 목록을 다시 확인해주세요.");
            }
        });
    }

    /**
     * 미체결 주문을 모두 취소한다.
     *
     * <p>한 건이라도 실패하면 몇 건이 남았는지 함께 알린다. 일부만 취소되었는데 전부
     * 취소되었다고 안내하면, 남은 주문이 그대로 체결될 수 있다.
     */
    private void cancelAllOrders(TableView<ObservableList<String>> table) {
        List<String> orderIds = tradingUseCase.openOrders().stream().map(Order::orderId).toList();
        if (orderIds.isEmpty()) {
            showInformation("취소할 주문이 없습니다", "미체결 주문이 없습니다.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "미체결 주문 " + orderIds.size() + "건을 모두 취소하시겠습니까?",
                ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("미체결 전량 취소 재확인");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            int cancelled = 0;
            List<String> failures = new java.util.ArrayList<>();
            for (String orderId : orderIds) {
                try {
                    tradingUseCase.cancel(orderId);
                    cancelled++;
                } catch (RuntimeException failure) {
                    failures.add(orderId);
                }
            }
            String message = failures.isEmpty()
                    ? "미체결 주문 " + cancelled + "건을 취소했습니다."
                    : "미체결 주문 " + cancelled + "건을 취소했고 " + failures.size()
                            + "건은 취소하지 못했습니다. 주문번호 " + String.join(", ", failures);
            status.setText(message);
            addNotification("주문", message);
            announce(message, failures.isEmpty() ? SpeechPriority.ORDER : SpeechPriority.CRITICAL,
                    "order-cancel-all");
            play(failures.isEmpty() ? SoundCue.SUCCESS : SoundCue.ERROR);
            screenController.invalidate(Screen.TRADING);
            screenController.invalidate(Screen.ACCOUNT);
        });
    }

    private void showInformation(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(title); alert.showAndWait();
    }

    /**
     * 알림을 쌓는다.
     *
     * <p>알림 화면의 분류 필터가 {@code · 분류 ·} 형태를 찾으므로 표기를 맞춘다. 읽지 않은
     * 알림은 앞에 표시를 붙여, 목록을 소리로 훑을 때도 새 알림을 구분할 수 있게 한다.
     *
     * @param category 주문, 가격, 이상 감지, 연결 중 하나
     */
    private void addNotification(String category, String message) {
        String stamp = java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        session.notifications().add(0, "새 알림 · " + stamp + " · " + category + " · " + message);
        // 오래된 알림이 무한정 쌓이지 않게 한다.
        while (session.notifications().size() > 200) {
            session.notifications().remove(session.notifications().size() - 1);
        }
    }

    /** 손익 금액을 부호와 함께 표기한다. */
    private static String signedWon(BigDecimal value) {
        return (value.signum() >= 0 ? "+" : "-") + Formatters.won(value.abs());
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
                addNotification("주문", receiptMessage + " 주문번호 " + receipt.orderId());
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
            String reason = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            status.setText("주문 입력 오류: " + reason);
            // 실패도 알림에 남긴다. 소리로만 알리면 지나간 뒤에 확인할 방법이 없다.
            addNotification("주문", "주문이 처리되지 않았습니다. " + reason);
            announce("주문 입력 오류. " + reason, SpeechPriority.CRITICAL, "order-input-error"); play(SoundCue.ERROR);
            Alert alert = new Alert(Alert.AlertType.ERROR, reason, ButtonType.OK); alert.setHeaderText("주문 입력을 확인하세요."); alert.showAndWait();
        }
    }

    private HBox createStatusBar() {
        status.setWrapText(true);
        // 지금 보는 값이 실제 시세인지 가짜 데이터인지 항상 드러낸다. 화면을 볼 수 없는
        // 사용자가 조회 결과의 출처를 확인할 수 있어야 한다.
        boolean live = marketDataSource.startsWith("키움");
        Label rest = new Label(live ? "REST " + marketDataSource : "REST 미연결");
        rest.getStyleClass().addAll("status-item", live ? "status-live" : "status-mock");
        rest.setAccessibleText(live
                ? "시세 공급원. " + marketDataSource + " 에 연결되어 있습니다."
                : "시세 공급원. 증권사에 연결되어 있지 않습니다. " + marketDataSource);
        Label realtime = new Label("실시간 미연결"); realtime.getStyleClass().addAll("status-item", "status-mock");
        Label mode = new Label("모의투자"); mode.getStyleClass().addAll("status-item", "status-mock");
        Label subscriptions = new Label("실시간 구독 0 / 200"); subscriptions.getStyleClass().add("status-item");
        lastDataTime.setText(live ? "조회 시세 · 요청 시점 기준" : "데모 시세 · 로컬 스냅샷");
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
