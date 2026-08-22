package org.ossproject.desktop;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
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
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.port.MarketApplicationListener;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.anomaly.AnomalyAlert;
import org.ossproject.anomaly.AnomalySeverity;
import org.ossproject.anomaly.StreamingAnomalyConfig;
import org.ossproject.anomaly.StreamingAnomalyDetector;
import org.ossproject.desktop.composition.DesktopServices;
import org.ossproject.finance.model.*;
import org.ossproject.desktop.ai.AiInsightListPanel;

import java.util.LinkedHashMap;
import org.ossproject.desktop.viewmodel.AiInsightViewModel;
import org.ossproject.desktop.view.StockPicker;
import org.ossproject.desktop.view.WatchlistToggle;
import org.ossproject.desktop.view.screen.NewsScreenView;
import org.ossproject.desktop.view.screen.SettingsScreenView;
import org.ossproject.desktop.view.screen.SimilarScreenView;
import org.ossproject.desktop.view.screen.StockComparisonDialog;
import org.ossproject.ai.SimilarStock;
import org.ossproject.desktop.viewmodel.NewsViewModel;
import org.ossproject.desktop.ai.AiServiceProcess;
import org.ossproject.desktop.chart.AccessibleChartController;
import org.ossproject.desktop.chart.AccessibleChartView;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.desktop.presentation.Formatters;

import static org.ossproject.desktop.presentation.Formatters.assetsSource;
import static org.ossproject.desktop.presentation.Formatters.orderTime;
import static org.ossproject.desktop.presentation.Formatters.signedChangeRate;
import static org.ossproject.desktop.presentation.Formatters.signedWon;
import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.controller.DesktopScreenController;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.secret.SecretStore;
import org.ossproject.desktop.viewmodel.DesktopSession;
import org.ossproject.desktop.viewmodel.StockSearchItem;
import org.ossproject.desktop.viewmodel.StockSearchViewModel;
import org.ossproject.desktop.viewmodel.ConnectionViewModel;
import org.ossproject.desktop.viewmodel.WatchlistViewModel;
import org.ossproject.desktop.orderbook.DepthChartCanvas;
import org.ossproject.desktop.orderbook.OrderBookLadderView;
import org.ossproject.desktop.trades.TradeTapeView;
import org.ossproject.desktop.viewmodel.AccountScreenData;
import org.ossproject.desktop.viewmodel.OrderBookViewModel;
import org.ossproject.desktop.viewmodel.TradeTapeViewModel;
import org.ossproject.desktop.viewmodel.StockDetailViewModel;
import org.ossproject.desktop.viewmodel.StockSelection;
import org.ossproject.desktop.viewmodel.ScannerViewModel;
import org.ossproject.desktop.view.screen.SearchScreenView;
import org.ossproject.desktop.view.screen.ConnectionScreenView;
import org.ossproject.desktop.view.screen.AccountScreenView;
import org.ossproject.desktop.view.screen.NotificationsScreenView;
import org.ossproject.desktop.view.screen.UsMarketScreenView;
import org.ossproject.desktop.view.screen.WatchlistScreenView;
import org.ossproject.desktop.view.screen.ScannerScreenView;
import org.ossproject.desktop.persistence.DesktopStateRepository;
import org.ossproject.desktop.persistence.DesktopStateSnapshot;
import org.ossproject.desktop.persistence.AccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.SonificationPreferencesRepository;
import org.ossproject.desktop.state.AccessibilityPreferences;
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
    private final CandleQueryPort candleAdapter;
    /** 시세 공급원 설명. 상태 표시줄에 그대로 보여 준다. */
    private final String marketDataSource;
    private final SpeechPort speechPort;
    private final SpeechQueue speechQueue;
    private final SoundPort soundPort;
    private final SonificationPort sonificationPort;
    private final SecretStore secretStore;
    private final AiInsightViewModel aiInsightViewModel;
    /** AI 서버를 앱이 띄웠으면 그 프로세스. 사용자가 직접 띄웠거나 못 띄웠으면 null. */
    private final NewsViewModel newsViewModel;
    /** 지금 보고 있는 화면. 결과가 늦게 와도 그때 살아 있는 화면에만 넣는다. */
    private SimilarScreenView similarView;
    private NewsScreenView newsView;
    /** 이상 감지 화면의 AI 분석 목록. 화면을 다시 만들면 새로 잡힌다. */
    private AiInsightListPanel aiInsightListPanel;
    /** 종목을 바꿔 다시 만든 화면. 고르개에 초점을 돌려 준다. */
    private Screen pickerFocusScreen;
    /**
     * 마지막으로 받은 분석.
     *
     * <p>챗봇이 근거로 쓴다. 서버가 다시 계산하면 그새 값이 바뀌어 사용자가 화면에서
     * 보고 있는 것과 다른 답을 듣는다.
     */
    private org.ossproject.ai.AiInsight lastInsight;
    private final AiServiceProcess aiServiceProcess;
    private AccessibleChartController accessibleChartController;
    private final Label status = new Label("준비됨");
    private final Label lastDataTime = new Label("마지막 시세 --:--:--");
    /** 실시간 연결 상태. 실제 스트림 상태를 그대로 옮긴다. */
    private final Label realtimeStatus = new Label("실시간 연결 끊김");
    private final Label subscriptionCount = new Label("실시간 구독 0");
    private EventSubscription connectionWatch;
    private Timeline subscriptionTicker;
    private final StackPane screenHost = new StackPane();
    private final Map<Screen, Button> navigationButtons = new EnumMap<>(Screen.class);
    private final DesktopSession session = new DesktopSession();
    private final StockSearchViewModel stockSearchViewModel;
    private final ConnectionViewModel connectionViewModel;
    private final WatchlistViewModel watchlistViewModel;
    private final StockDetailViewModel stockDetailViewModel;
    private final OrderBookViewModel orderBookViewModel;
    private final TradeTapeViewModel tradeTapeViewModel;
    private final StreamingAnomalyConfig anomalyConfig = StreamingAnomalyConfig.defaults();
    private final StreamingAnomalyDetector anomalyDetector = new StreamingAnomalyDetector(anomalyConfig);
    private final Map<String, EventSubscription> anomalySubscriptions = new java.util.concurrent.ConcurrentHashMap<>();
    private long anomalyMonitoringGeneration;
    /** 지금 보고 있는 호가창. 실시간이 멈췄는지 주기적으로 다시 표시하려고 들고 있는다. */
    private OrderBookLadderView orderBookLadder;

    /**
     * 이 시간 동안 호가가 오지 않으면 실시간이 멈춘 것으로 본다.
     *
     * <p>장중 활발한 종목은 초 단위로 오지만 한산한 종목은 몇십 초씩 비는 일이 있다.
     * 너무 짧게 잡으면 멀쩡한 연결을 끊긴 것처럼 알리게 된다.
     */
    private static final java.time.Duration ORDER_BOOK_STALE_AFTER = java.time.Duration.ofSeconds(30);
    private final ScannerViewModel scannerViewModel = new ScannerViewModel();
    private final DesktopStateRepository stateRepository;
    private final AccessibilityPreferencesRepository accessibilityPreferencesRepository;
    private final SonificationPreferencesRepository sonificationPreferencesRepository;
    private DesktopScreenController screenController;
    private PauseTransition persistenceDelay;
    private final TextField globalSearch = new TextField();
    private final ContextMenu globalSearchMenu = new ContextMenu();
    private final PauseTransition globalSearchDelay = new PauseTransition(Duration.millis(220));
    private ListView<StockSearchItem> globalSearchSuggestions;
    private ListView<String> globalRecentSearches;
    private Label globalSearchState;
    private VBox globalSearchPanel;
    private VBox globalRecentSection;
    private VBox globalSuggestionSection;
    private Label globalSearchKeyboardHelp;
    private boolean globalSearchSelectionInProgress;
    private boolean globalSearchPopupArmed;
    private final Button backButton = new Button("←");
    private final Button connectionButton = new Button("키움 실시간 · 확인 중");
    private final Label currentLocation = new Label("홈");
    private BorderPane root;
    private VBox autoHideSidebar;
    private final PauseTransition sidebarHideDelay = new PauseTransition(Duration.millis(70));
    /**
     * 접근성 설정.
     *
     * <p>값을 따로 들고 있으면 저장할 때마다 다시 묶어야 하고, 한 곳만 빠뜨려도 설정이
     * 조용히 사라진다. 통째로 들고 하나씩 바꿔 나간다.
     */
    private AccessibilityPreferences accessibility = AccessibilityPreferences.DEFAULT;
    private boolean preventDuplicateOrders = true;
    private SonificationPreferences sonificationPreferences = SonificationPreferences.DEFAULT;
    private String pendingOrderPrice = "";
    private OrderDraft orderDraft;
    private String lastSubmittedOrderFingerprint = "";
    private long lastSubmittedOrderNanos;
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
        this.aiInsightViewModel = new AiInsightViewModel(
                services.market(), services.aiInsight(), Platform::runLater);
        this.newsViewModel = new NewsViewModel(services.news(), Platform::runLater);
        this.aiServiceProcess = services.aiServiceProcess();
        this.stateRepository = services.stateRepository();
        this.accessibilityPreferencesRepository = services.accessibilityPreferences();
        this.sonificationPreferencesRepository = services.sonificationPreferences();
        this.connectionViewModel = new ConnectionViewModel(
                secretStore, DesktopServices::verifyMockCredentials);
        this.stockSearchViewModel = new StockSearchViewModel(
                session, marketApplication, Platform::runLater);
        this.watchlistViewModel = new WatchlistViewModel(
                session, marketApplication, Platform::runLater);
        this.stockDetailViewModel = new StockDetailViewModel(
                session, marketApplication, Platform::runLater);
        this.orderBookViewModel = new OrderBookViewModel(marketApplication, Platform::runLater);
        this.tradeTapeViewModel = new TradeTapeViewModel(marketApplication, Platform::runLater);
    }

    @Override public void start(Stage stage) {
        restoreLocalState();
        session.onChange(this::scheduleStateSave);
        stockSearchViewModel.recentSearches().addListener(
                (javafx.collections.ListChangeListener<String>) change -> scheduleStateSave());
        session.watchlistItems().addListener(
                (javafx.collections.ListChangeListener<WatchlistItem>) change -> refreshAnomalyMonitoring());
        root = new BorderPane();
        root.getStyleClass().add("app-root");
        if (accessibility.largeTextEnabled()) root.getStyleClass().add("large-text");
        if (accessibility.highContrastEnabled()) root.getStyleClass().add("high-contrast");
        if (accessibility.reducedMotionEnabled()) root.getStyleClass().add("reduced-motion");
        root.setMinSize(0, 0);
        screenHost.setMinSize(0, 0);
        root.setTop(createTopBar());
        root.setCenter(createWorkspace());
        applyKeyboardGuidance(accessibility.keyboardGuidanceEnabled());
        applyInformationDensity(accessibility.informationDensity());
        watchRealtimeConnection();
        status.setAccessibleText("앱 상태. " + status.getText());
        status.textProperty().addListener((obs, old, message) -> Platform.runLater(() -> {
            status.setAccessibleText("앱 상태. " + message);
            status.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
        }));
        configureScreens();
        refreshAnomalyMonitoring();
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
        // 컨텍스트 메뉴는 별도 윈도로 표시된다. 최소화할 때 닫아야 복원 후 검색창을
        // 누르지 않았는데 추천 패널이 다시 나타나는 현상을 막을 수 있다.
        stage.iconifiedProperty().addListener((obs, old, iconified) -> {
            if (iconified) {
                globalSearchPopupArmed = false;
                globalSearchDelay.stop();
                globalSearchMenu.hide();
            }
        });
        stage.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) {
                globalSearchPopupArmed = false;
                globalSearchMenu.hide();
            }
        });
        stage.setScene(scene); stage.show();
    }

    private VBox createSidebar() {
        Label product = new Label("OS");
        product.getStyleClass().add("nav-rail-logo");
        product.setAccessibleText("OpenStock Access");
        Tooltip.install(product, new Tooltip("OpenStock Access · " + marketDataSource));

        navigationButtons.clear();
        VBox nav = new VBox(0);
        nav.setAlignment(Pos.TOP_CENTER);
        Screen.NavigationGroup previousGroup = null;
        for (Screen screen : Screen.values()) {
            if (!screen.shownInSidebar()) continue;
            if (previousGroup != null && previousGroup != screen.navigationGroup()) {
                Separator separator = new Separator();
                separator.getStyleClass().add("nav-rail-separator");
                nav.getChildren().add(separator);
            }
            Button button = new Button();
            button.setGraphic(navigationIcon(screen));
            button.getStyleClass().addAll("nav-button", "nav-rail-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAccessibleText(screen.navigationGroup().label() + " 메뉴, " + screen.label() + " 화면 열기");
            button.setAccessibleHelp("Enter 또는 Space로 " + screen.label() + " 화면을 엽니다.");
            button.setTooltip(new Tooltip(screen.label()));
            button.setOnAction(event -> openNavigationScreen(screen));
            navigationButtons.put(screen, button);
            nav.getChildren().add(button);
            previousGroup = screen.navigationGroup();
        }

        ScrollPane navScroll = new ScrollPane(nav);
        navScroll.getStyleClass().add("sidebar-scroll");
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(navScroll, Priority.ALWAYS);
        VBox sidebar = new VBox(8, product, navScroll);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_CENTER);
        sidebar.setPadding(new Insets(8, 6, 8, 6));
        sidebar.setPrefWidth(72);
        sidebar.setMinWidth(72);
        sidebar.setMaxWidth(72);
        return sidebar;
    }

    private StackPane createWorkspace() {
        autoHideSidebar = createSidebar();
        autoHideSidebar.setVisible(false);
        autoHideSidebar.setManaged(false);
        autoHideSidebar.setOnMouseEntered(event -> showSidebar());
        autoHideSidebar.setOnMouseExited(event -> scheduleSidebarHide());

        Region hotspot = new Region();
        hotspot.getStyleClass().add("sidebar-hotspot");
        hotspot.setMinWidth(18);
        hotspot.setPrefWidth(18);
        hotspot.setMaxWidth(18);
        hotspot.setMaxHeight(Double.MAX_VALUE);
        hotspot.setAccessibleText("왼쪽 네비게이션 열기 영역");
        hotspot.setOnMouseEntered(event -> showSidebar());

        sidebarHideDelay.setOnFinished(event -> hideSidebar());
        StackPane workspace = new StackPane(screenHost, hotspot, autoHideSidebar);
        StackPane.setAlignment(hotspot, Pos.TOP_LEFT);
        StackPane.setAlignment(autoHideSidebar, Pos.TOP_LEFT);
        workspace.getStyleClass().add("workspace");
        workspace.setMinSize(0, 0);
        workspace.setOnMouseMoved(event -> {
            if (event.getX() <= 24) showSidebar();
            else if (autoHideSidebar != null && autoHideSidebar.isVisible() && event.getX() > 82) {
                scheduleSidebarHide();
            }
        });
        return workspace;
    }

    private void showSidebar() {
        sidebarHideDelay.stop();
        if (autoHideSidebar == null) return;
        autoHideSidebar.setManaged(true);
        autoHideSidebar.setVisible(true);
        autoHideSidebar.toFront();
    }

    private void scheduleSidebarHide() {
        if (sidebarHasFocus()) return;
        sidebarHideDelay.playFromStart();
    }

    private void hideSidebar() {
        if (sidebarHasFocus()) return;
        autoHideSidebar.setVisible(false);
        autoHideSidebar.setManaged(false);
    }

    private boolean sidebarHasFocus() {
        return autoHideSidebar != null && root != null && root.getScene() != null
                && isDescendantOf(root.getScene().getFocusOwner(), autoHideSidebar);
    }

    private Node navigationIcon(Screen screen) {
        String data = switch (screen) {
            case DASHBOARD -> "M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z";
            case CONNECTION -> "M7.5 6h3v2h-3a4 4 0 0 0 0 8h3v2h-3a6 6 0 0 1 0-12h3v2h-3a4 4 0 0 0 0-8zm2.5 5h4v2h-4zm3.5-5h3a6 6 0 1 1 0 12h-3v-2h3a4 4 0 1 0 0-8h-3z";
            case MARKET -> "M4 4h7v7H4zm9 0h7v7h-7zM4 13h7v7H4zm9 0h7v7h-7z";
            case SEARCH, STOCK_DETAIL -> "M10 3a7 7 0 1 0 4.9 12l5.6 5.5 1.5-1.5-5.5-5.6A7 7 0 0 0 10 3zm0 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z";
            case WATCHLIST -> "M12 2.5l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5-5.8-3-5.8 3 1.1-6.5-4.7-4.6 6.5-.9z";
            case SCANNER -> "M4 4h16v3H4zm0 6h11v3H4zm0 6h7v3H4z";
            case CONDITION -> "M5 4h14v3H5zm2 6h10v3H7zm3 6h4v3h-4z";
            case TRADING -> "M3 5h16a2 2 0 0 1 2 2v2h-5a3 3 0 0 0 0 0 6h5v2a2 2 0 0 1-2 2H3zm13 6h6v2h-6a1 1 0 0 1 0-2z";
            case ACCOUNT -> "M4 4h16v16H4zm3 4v2h10V8zm0 4v2h10v-2zm0 4v2h6v-2z";
            case US_MARKET -> "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm6.9 9h-3a15.8 15.8 0 0 0-1.2-5A8 8 0 0 1 18.9 11zM12 4c1.1 1.3 1.8 3.8 1.9 7h-3.8c.1-3.2.8-5.7 1.9-7zM9.3 6A15.8 15.8 0 0 0 8.1 11h-3A8 8 0 0 1 9.3 6zM5.1 13h3a15.8 15.8 0 0 0 1.2 5 8 8 0 0 1-4.2-5zM12 20c-1.1-1.3-1.8-3.8-1.9-7h3.8c-.1 3.2-.8 5.7-1.9 7zm2.7-2a15.8 15.8 0 0 0 1.2-5h3a8 8 0 0 1-4.2 5z";
            // 겹친 물결 두 줄. 닮은 모양을 겹쳐 놓았다는 뜻이다.
            case SIMILAR -> "M3 15c3-6 6-6 9 0s6 6 9 0v3c-3 6-6 6-9 0s-6-6-9 0zm0-8c3-6 6-6 9 0s6 6 9 0v3c-3 6-6 6-9 0s-6-6-9 0z";
            // 접힌 신문.
            case NEWS -> "M4 4h13v16H4zm2 3v2h9V7zm0 4v2h9v-2zm0 4v2h6v-2zm13-8h3v11a2 2 0 0 1-4 0V7z";
            case ANOMALY -> "M12 2 1 21h22zm0 5.2-6.2 11.3h12.4zM11 10h2v4h-2zm0 5.5h2v2h-2z";
            case NOTIFICATIONS -> "M12 22a2.5 2.5 0 0 0 2.4-2h-4.8A2.5 2.5 0 0 0 12 22zM20 17H4l2-2v-5a6 6 0 0 1 5-5.9V2h2v2.1A6 6 0 0 1 18 10v5z";
            case RADIO -> "M9 4v12.2a3 3 0 1 0 2 2.8V8h7V4zm-3 16a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm9-2a1 1 0 1 1 0-2 1 1 0 0 1 0 2z";
            case SETTINGS -> "M19.4 13a7.7 7.7 0 0 0 .1-1l2-1.5-2-3.5-2.5 1a8 8 0 0 0-1.7-1L15 4h-4l-.4 3a8 8 0 0 0-1.7 1L6.5 7 4.5 10.5l2 1.5a7.7 7.7 0 0 0 0 2L4.5 15.5 6.5 19 9 18a8 8 0 0 0 1.7 1l.3 3h4l.4-3a8 8 0 0 0 1.7-1l2.5 1 2-3.5zM13 16a4 4 0 1 1 0-8 4 4 0 0 1 0 8z";
        };
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent(data);
        icon.getStyleClass().add("nav-rail-icon");
        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().add("nav-rail-icon-box");
        iconBox.setMinSize(24, 24);
        iconBox.setPrefSize(24, 24);
        iconBox.setMaxSize(24, 24);
        return iconBox;
    }

    private void openNavigationScreen(Screen screen) {
        if (screen == Screen.TRADING) openOrder(OrderSide.BUY);
        else if (screen == Screen.SEARCH) {
            stockSearchViewModel.prepare("", "전체");
            screenController.invalidate(Screen.SEARCH);
            navigate(Screen.SEARCH);
        }
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
        globalSearch.setPrefWidth(360);
        globalSearch.setMinWidth(180);
        globalSearch.setOnAction(event -> openSearchedStock());
        configureGlobalSearchMenu();

        Button searchButton = new Button("검색");
        searchButton.setOnAction(event -> openSearchedStock());
        HBox search = new HBox(8, globalSearch, searchButton);
        search.getStyleClass().add("global-search-shell");
        search.setAlignment(Pos.CENTER_LEFT);
        search.setMinWidth(240);
        search.setPrefWidth(420);
        search.setMaxWidth(560);
        HBox.setHgrow(globalSearch, Priority.ALWAYS);

        // 상단 표시는 실제 상태를 따른다. 연결되어 있는데 미연결로 보이거나 그 반대면,
        // 화면을 볼 수 없는 사용자는 지금 값이 실제 시세인지 판단할 근거를 잃는다.
        Label market = new Label("시세 공급원 · " + marketDataSource);
        market.getStyleClass().addAll("status-chip", "mode-badge");
        market.setAccessibleText("시세 출처. " + marketDataSource);
        connectionButton.getStyleClass().add("connection-button");
        connectionButton.setOnAction(event -> navigate(Screen.CONNECTION));

        Button alerts = new Button();
        alerts.setOnAction(event -> navigate(Screen.NOTIFICATIONS));
        Runnable refreshAlertCount = () -> {
            int count = (int) session.notifications().stream()
                    .filter(notification -> notification.startsWith("새 알림 · "))
                    .count();
            alerts.setText("알림 " + count);
            alerts.setAccessibleText(count == 0 ? "새 알림 없음" : "새 알림 " + count + "건");
        };
        session.notifications().addListener(
                (javafx.collections.ListChangeListener<String>) change -> refreshAlertCount.run());
        refreshAlertCount.run();

        // 계좌번호는 증권사에서 받아야 알 수 있다. 임의의 번호를 보여 주지 않는다.
        Button account = new Button("계좌");
        account.setAccessibleText("계좌 화면 열기");
        account.setOnAction(event -> navigate(Screen.ACCOUNT));

        HBox.setHgrow(search, Priority.ALWAYS);
        HBox context = new HBox(10, backButton, currentLocation, search, market, alerts, account, connectionButton);
        context.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(context);
        top.getStyleClass().add("top-bar");
        top.setPadding(new Insets(9, 14, 9, 14));
        return top;
    }

    /**
     * 상단 검색창에 최근 검색과 자동완성 결과를 붙인다.
     *
     * <p>검색 화면의 {@link StockSearchViewModel}을 그대로 재사용해 상단 검색과
     * 전체 검색 화면이 서로 다른 검색 기록을 만들지 않게 한다.</p>
     */
    private void configureGlobalSearchMenu() {
        if (!globalSearchMenu.getItems().isEmpty()) return;

        Label recentTitle = new Label("최근 검색");
        recentTitle.getStyleClass().add("search-popup-title");
        globalRecentSearches = new ListView<>(stockSearchViewModel.recentSearches());
        globalRecentSearches.setAccessibleText("최근 검색 목록");
        globalRecentSearches.setAccessibleHelp("위아래 방향키로 선택하고 Enter를 누르면 종목 상세를 엽니다. Delete를 누르면 기록을 삭제합니다.");
        globalRecentSearches.setPrefHeight(250);
        globalRecentSearches.setPlaceholder(new Label("아직 최근 검색이 없습니다."));
        globalRecentSearches.setCellFactory(list -> recentSearchCell());
        globalRecentSearches.setOnMouseClicked(event -> {
            if (event.getTarget() instanceof Node target && isInsideButton(target)) return;
            String recent = globalRecentSearches.getSelectionModel().getSelectedItem();
            if (recent != null) openGlobalRecentSearch(recent);
        });
        globalRecentSearches.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                String recent = globalRecentSearches.getSelectionModel().getSelectedItem();
                if (recent != null) openGlobalRecentSearch(recent);
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE) {
                String recent = globalRecentSearches.getSelectionModel().getSelectedItem();
                stockSearchViewModel.removeRecent(recent);
                if (recent != null) status.setText(recent + " 최근 검색을 삭제했습니다.");
                event.consume();
            }
        });
        globalRecentSection = new VBox(8, recentTitle, globalRecentSearches);

        Label suggestionTitle = new Label("검색어 자동완성");
        suggestionTitle.getStyleClass().add("search-popup-title");
        globalSearchState = new Label("검색어를 입력하면 종목을 찾습니다.");
        globalSearchState.getStyleClass().add("muted-text");
        globalSearchState.setWrapText(true);
        globalSearchSuggestions = new ListView<>(stockSearchViewModel.items());
        globalSearchSuggestions.setAccessibleText("종목 검색어 자동완성 목록");
        globalSearchSuggestions.setAccessibleHelp("위아래 방향키로 선택하고 Enter를 누르면 종목 상세를 엽니다.");
        globalSearchSuggestions.setPrefHeight(300);
        globalSearchSuggestions.setPlaceholder(new Label("검색 결과가 없습니다."));
        globalSearchSuggestions.setCellFactory(list -> suggestionCell());
        globalSearchSuggestions.setOnMouseClicked(event -> {
            StockSearchItem selected = globalSearchSuggestions.getSelectionModel().getSelectedItem();
            if (selected != null) openGlobalSearchSuggestion(selected);
        });
        globalSearchSuggestions.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                StockSearchItem selected = globalSearchSuggestions.getSelectionModel().getSelectedItem();
                if (selected != null) openGlobalSearchSuggestion(selected);
                event.consume();
            }
        });
        globalSuggestionSection = new VBox(8, suggestionTitle, globalSearchState, globalSearchSuggestions);

        globalSearchKeyboardHelp = new Label("↑↓ 선택  ·  Enter 열기  ·  Esc 닫기");
        globalSearchKeyboardHelp.getStyleClass().add("search-popup-help");
        globalSearchKeyboardHelp.setVisible(accessibility.keyboardGuidanceEnabled());
        globalSearchKeyboardHelp.setManaged(accessibility.keyboardGuidanceEnabled());
        globalSearchPanel = new VBox(10, globalRecentSection, globalSuggestionSection, globalSearchKeyboardHelp);
        globalSearchPanel.getStyleClass().add("search-suggestion-panel");
        globalSearchPanel.setAccessibleText("종목 검색 추천 패널");

        CustomMenuItem content = new CustomMenuItem(globalSearchPanel, false);
        content.getStyleClass().add("search-popup-menu-item");
        globalSearchMenu.getItems().add(content);
        globalSearchMenu.getStyleClass().add("search-suggestion-popup");
        globalSearchMenu.setAutoHide(true);
        globalSearchMenu.setOnHidden(event -> globalSearchPopupArmed = false);

        globalSearchDelay.setOnFinished(event -> refreshGlobalSearchSuggestions());
        globalSearch.textProperty().addListener((observable, previous, query) -> {
            if (globalSearchSelectionInProgress) return;
            boolean blank = query == null || query.isBlank();
            updateGlobalSearchSections(blank);
            globalSearchDelay.stop();
            if (!blank) {
                globalSearchState.setText("종목을 찾고 있습니다.");
                globalSearchDelay.playFromStart();
            }
            showGlobalSearchMenu();
        });
        globalSearch.setOnMouseClicked(event -> {
            globalSearchPopupArmed = true;
            showGlobalSearchMenu();
        });
        globalSearch.addEventFilter(KeyEvent.KEY_TYPED, event -> globalSearchPopupArmed = true);
        globalSearch.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DOWN && globalSearchMenu.isShowing()) {
                if (globalSearch.getText() == null || globalSearch.getText().isBlank()) {
                    if (!globalRecentSearches.getItems().isEmpty()) {
                        globalRecentSearches.getSelectionModel().selectFirst();
                        globalRecentSearches.requestFocus();
                    }
                } else if (!globalSearchSuggestions.getItems().isEmpty()) {
                    globalSearchSuggestions.getSelectionModel().selectFirst();
                    globalSearchSuggestions.requestFocus();
                }
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                globalSearchPopupArmed = false;
                globalSearchMenu.hide();
                event.consume();
            }
        });
    }

    private ListCell<String> recentSearchCell() {
        return new ListCell<>() {
            private final Label history = new Label("↺");
            private final Label value = new Label();
            private final Button remove = new Button("삭제");
            private final Region spacer = new Region();
            private final HBox row = new HBox(10, history, value, spacer, remove);
            {
                row.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                value.setMaxWidth(Double.MAX_VALUE);
                remove.getStyleClass().add("search-history-remove");
                remove.setOnAction(event -> {
                    String item = getItem();
                    stockSearchViewModel.removeRecent(item);
                    if (item != null) status.setText(item + " 최근 검색을 삭제했습니다.");
                });
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    value.setText(item);
                    setText(null);
                    setGraphic(row);
                }
            }
        };
    }

    private ListCell<StockSearchItem> suggestionCell() {
        return new ListCell<>() {
            private final Label icon = new Label("⌕");
            private final Label name = new Label();
            private final Label detail = new Label();
            private final VBox labels = new VBox(2, name, detail);
            private final HBox row = new HBox(11, icon, labels);
            {
                row.setAlignment(Pos.CENTER_LEFT);
                name.getStyleClass().add("search-suggestion-name");
                detail.getStyleClass().add("search-suggestion-detail");
            }
            @Override protected void updateItem(StockSearchItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    name.setText(item.name() + "  " + item.symbol());
                    detail.setText(item.market() + " · " + item.exchange() + " · " + item.price());
                    setAccessibleText(item.accessibleDescription());
                    setText(null);
                    setGraphic(row);
                }
            }
        };
    }

    private void refreshGlobalSearchSuggestions() {
        String query = globalSearch.getText() == null ? "" : globalSearch.getText().trim();
        if (query.isBlank()) return;
        stockSearchViewModel.filter(query, "전체").whenComplete((result, failure) -> Platform.runLater(() -> {
            if (!query.equals(globalSearch.getText().trim()) || !result.applied()) return;
            if (failure != null || !stockSearchViewModel.lastError().isBlank()) {
                globalSearchState.setText("종목을 불러오지 못했습니다. 연결 상태를 확인해주세요.");
            } else {
                globalSearchState.setText(result.count() == 0
                        ? "일치하는 종목이 없습니다."
                        : "검색 결과 " + result.count() + "건");
                if (result.count() > 0) globalSearchSuggestions.getSelectionModel().selectFirst();
            }
            showGlobalSearchMenu();
        }));
    }

    private void updateGlobalSearchSections(boolean showRecent) {
        globalRecentSection.setVisible(showRecent);
        globalRecentSection.setManaged(showRecent);
        globalSuggestionSection.setVisible(!showRecent);
        globalSuggestionSection.setManaged(!showRecent);
    }

    private void showGlobalSearchMenu() {
        if (globalSearch.getScene() == null || globalSearch.getScene().getWindow() == null
                || !globalSearch.getScene().getWindow().isShowing() || !globalSearch.isFocused()
                || !globalSearchPopupArmed) return;
        updateGlobalSearchSections(globalSearch.getText() == null || globalSearch.getText().isBlank());
        globalSearchPanel.setPrefWidth(Math.max(460, globalSearch.getWidth()));
        if (!globalSearchMenu.isShowing()) globalSearchMenu.show(globalSearch, Side.BOTTOM, 0, 7);
    }

    private void openGlobalSearchSuggestion(StockSearchItem selected) {
        globalSearchSelectionInProgress = true;
        globalSearch.setText(selected.name());
        globalSearch.positionCaret(globalSearch.getText().length());
        globalSearchSelectionInProgress = false;
        globalSearchMenu.hide();
        stockSearchViewModel.select(selected);
        navigate(Screen.STOCK_DETAIL);
    }

    private void openGlobalRecentSearch(String recent) {
        globalSearchMenu.hide();
        status.setText(recent + " 종목을 다시 찾고 있습니다.");
        stockSearchViewModel.selectRecent(recent).whenComplete((opened, failure) -> Platform.runLater(() -> {
            if (failure != null || !opened) {
                status.setText(recent + " 종목을 다시 찾지 못했습니다.");
            } else {
                globalSearchSelectionInProgress = true;
                globalSearch.setText(recent.substring(0, recent.lastIndexOf(" · ")));
                globalSearch.positionCaret(globalSearch.getText().length());
                globalSearchSelectionInProgress = false;
                navigate(Screen.STOCK_DETAIL);
            }
        }));
    }

    private boolean isInsideButton(Node target) {
        for (Node node = target; node != null; node = node.getParent()) {
            if (node instanceof Button) return true;
        }
        return false;
    }

    private void focusGlobalSearch() {
        globalSearchPopupArmed = true;
        globalSearch.requestFocus();
        globalSearch.selectAll();
        Platform.runLater(this::showGlobalSearchMenu);
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
        showSidebar();
        Screen current = screenController == null
                ? Screen.DASHBOARD
                : screenController.currentScreen().orElse(Screen.DASHBOARD);
        Button target = navigationButtons.get(current);
        if (target == null && current == Screen.STOCK_DETAIL) target = navigationButtons.get(Screen.SEARCH);
        if (target == null) target = navigationButtons.get(Screen.DASHBOARD);
        if (target != null) target.requestFocus();
    }

    private boolean isDescendantOf(Node node, Node ancestor) {
        if (!(ancestor instanceof Parent) || node == null) return false;
        for (Node candidate = node; candidate != null; candidate = candidate.getParent()) {
            if (candidate == ancestor) return true;
        }
        return false;
    }

    private void openSearchedStock() {
        globalSearchMenu.hide();
        if (globalSearch.getText() == null || globalSearch.getText().isBlank()) {
            stockSearchViewModel.prepare("", "전체");
            screenController.invalidate(Screen.SEARCH);
            navigate(Screen.SEARCH);
            return;
        }
        String query = globalSearch.getText().trim();
        stockSearchViewModel.recordRecentQuery(query);
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

    private javafx.scene.Node shortfallWarning(Deposits deposits) {
        String text = "미수금 " + Formatters.won(deposits.shortfall())
                + "이 발생했습니다. 결제일까지 입금하지 않으면 반대매매가 될 수 있습니다.";
        Label warning = new Label(text);
        warning.getStyleClass().add("safety-note");
        warning.setWrapText(true);
        warning.setAccessibleText(text);
        return warning;
    }

    /**
     * 총자산 옆에 붙일 출처 설명.
     *
     * <p>증권사가 계산한 값과 앱이 더한 값은 다를 수 있다. 어느 쪽인지 밝히지 않으면
     * 사용자가 증권사 화면과 대조할 때 어느 숫자를 믿어야 할지 알 수 없다.
     */

    /**
     * 실시간 체결로 마지막 봉을 갱신받기 시작한다.
     *
     * <p>그래프와 접근 가능한 표가 같은 값을 보도록 함께 갱신한다. 한쪽만 갱신하면 화면을
     * 볼 수 없는 사용자가 표에서 읽는 값이 그래프와 달라진다.
     */
    private void startLiveChart(CandlestickChartView candleChart, TableView<PricePoint> history) {
        try {
            stockDetailViewModel.startLiveChart(points -> {
                candleChart.setPoints(points);
                history.getItems().setAll(points);
            });
            refreshSubscriptionCount();
        } catch (RuntimeException failure) {
            // 실시간이 없어도 조회한 차트는 그대로 볼 수 있다. 조용히 넘기지 않고 알린다.
            status.setText("실시간 차트 갱신을 시작하지 못했습니다. " + failure.getMessage());
        }
    }

    /**
     * 실시간 연결 상태를 상태 표시줄에 계속 반영한다.
     *
     * <p>전에는 "실시간 미연결" 이 고정 문자열이라, 실제로 붙어 있어도 끊긴 것처럼 보였다.
     * 화면을 볼 수 없는 사용자는 이 표시 말고 연결을 확인할 방법이 없다.
     */
    private void watchRealtimeConnection() {
        connectionWatch = marketApplication.observeConnection(this::applyConnectionState);
        // 구독은 차트, 청각 차트, 관심종목 등 여러 곳에서 생기고 사라진다. 각 지점마다
        // 갱신을 넣으면 하나만 빠뜨려도 표시가 실제와 어긋난다. 실제 값을 주기적으로 읽는다.
        subscriptionTicker = new Timeline(
                new KeyFrame(Duration.seconds(1), event -> {
                    refreshSubscriptionCount();
                    refreshOrderBookLiveness();
                }));
        subscriptionTicker.setCycleCount(Timeline.INDEFINITE);
        subscriptionTicker.play();
    }

    /** 연결 상태를 글자와 접근 가능한 이름, 구독 수에 함께 옮긴다. */
    private void applyConnectionState(ConnectionState state, String detail) {
        realtimeStatus.setText("실시간 " + state.displayName());
        realtimeStatus.getStyleClass().removeAll("status-live", "status-mock");
        realtimeStatus.getStyleClass().add(state.isUsable() ? "status-live" : "status-mock");
        connectionButton.setText("키움 실시간 · " + state.displayName());
        connectionButton.getStyleClass().removeAll("status-live", "status-mock");
        connectionButton.getStyleClass().add(state.isUsable() ? "status-live" : "status-mock");
        realtimeStatus.setAccessibleText("실시간 시세 연결. " + state.displayName()
                + (detail == null || detail.isBlank() ? "" : ". " + detail));
        refreshSubscriptionCount();
        if (detail != null && !detail.isBlank()) {
            status.setText("실시간 " + state.displayName() + ". " + detail);
        }
    }

    /** 구독 수는 화면을 오갈 때마다 달라진다. */
    /** 호가가 끊긴 것은 아무 일도 일어나지 않는 형태로 나타난다. 주기적으로 확인한다. */
    private void refreshOrderBookLiveness() {
        OrderBookLadderView current = orderBookLadder;
        if (current != null) {
            current.setLive(orderBookViewModel.isLive(ORDER_BOOK_STALE_AFTER));
        }
    }

    private void refreshSubscriptionCount() {
        int count = marketApplication.liveSubscriptionCount();
        subscriptionCount.setText("실시간 구독 " + count + "개");
        subscriptionCount.setAccessibleText("현재 실시간 구독 종목 " + count + "개.");
    }

    /**
     * 실시간 호가창.
     *
     * <p>공급원이 호가를 주지 않으면 호가창 대신 안내를 보여 준다. 빈 표를 띄우면 잔량이
     * 없는 것인지 연결이 안 된 것인지 구분할 수 없다.
     */
    private javafx.scene.Node createOrderBookPanel(String stockName) {
        OrderBookLadderView ladder = new OrderBookLadderView(stockName);
        DepthChartCanvas depth = new DepthChartCanvas();
        // 표가 원본이고 그래프는 보조다. 차트 탭과 같은 순서로 둔다.
        TabPane views = new TabPane(tab("호가 표", ladder.root()), tab("누적 깊이 그래프", depth));
        views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // 높이를 고정하면 호가 표가 잘려 아래 단계에 아예 닿을 수 없다. 내용에 맞춰 늘어나게
        // 두고, 화면이 길어지면 바깥 스크롤로 닿는다.
        views.setMinSize(0, 0);
        views.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        views.getStyleClass().add("order-book-panel");

        if (!orderBookViewModel.supported()) {
            ladder.showUnavailable("실시간 호가를 제공하지 않는 연결입니다. " + marketDataSource);
            return views;
        }
        ladder.showUnavailable("호가를 기다리고 있습니다.");
        orderBookLadder = ladder;
        try {
            orderBookViewModel.start(session.selectedStock().securityId(), view -> {
                // 체결가를 아직 못 받았으면 격자 중심은 호가 중간값이다. 이름을 구분한다.
                ladder.setTradedCenter(orderBookViewModel.centeredOnTradedPrice());
                ladder.setLive(orderBookViewModel.isLive(ORDER_BOOK_STALE_AFTER));
                ladder.update(view);
            }, depth::update, walls -> ladder.showWalls(walls.orElse(null)));
        } catch (RuntimeException failure) {
            ladder.showUnavailable("호가 구독을 시작하지 못했습니다. " + failure.getMessage());
        }
        refreshSubscriptionCount();
        return views;
    }

    /**
     * 주문 확인 창에 넣을 비용 줄.
     *
     * <p>총액만 보여 주면 체결 뒤에야 차이를 알게 된다. 요율을 모르면 지어내지 않고
     * 모른다고 적는다.
     */
    private static String costLines(TradePreview preview) {
        TradeCosts costs = preview.costs();
        if (!costs.isKnown()) {
            return "\n수수료와 세금: 요율이 설정되지 않아 계산하지 않았습니다";
        }
        String settlementLabel = preview.command().side() == OrderSide.SELL
                ? "\n예상 수령금액: " : "\n예상 결제금액: ";
        return "\n예상 수수료: " + Formatters.won(costs.commission())
                + (costs.tax().signum() > 0 ? "\n예상 거래세: " + Formatters.won(costs.tax()) : "")
                + settlementLabel + Formatters.won(preview.settlementAmount());
    }

    /**
     * 실시간 체결 목록.
     *
     * <p>표에 초점이 있는 동안에는 갱신을 멈춘다. 활발한 종목은 초당 수십 건이 들어오는데,
     * 읽는 중에 목록이 위로 밀리면 스크린리더로 읽던 자리를 잃는다.
     */
    private javafx.scene.Node createTradeTapePanel(String stockName) {
        // 초점이 떠나 밀린 체결을 풀 때는 뷰모델이 갱신 통로로 다시 알려 준다.
        TradeTapeView tape = new TradeTapeView(stockName, tradeTapeViewModel::setPaused);
        if (!tradeTapeViewModel.supported()) {
            tape.showUnavailable("실시간 체결을 제공하지 않는 연결입니다. " + marketDataSource);
            return tape.root();
        }
        try {
            tradeTapeViewModel.start(session.selectedStock().securityId(),
                    tape::update, tape::showHeld);
        } catch (RuntimeException failure) {
            tape.showUnavailable("체결 구독을 시작하지 못했습니다. " + failure.getMessage());
        }
        refreshSubscriptionCount();
        return tape.root();
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
            // 종목 상세를 떠나면 봉 구독을 놓는다. 보이지 않는 차트를 계속 갱신할 이유가 없다.
            if (old == Screen.ANOMALY && screen != Screen.ANOMALY) {
                aiInsightViewModel.cancel();
            }
            if (old == Screen.STOCK_DETAIL && screen != Screen.STOCK_DETAIL) {
                stockDetailViewModel.stopLiveChart();
                tradeTapeViewModel.stop();
            }
            if ((old == Screen.STOCK_DETAIL || old == Screen.TRADING)
                    && screen != Screen.STOCK_DETAIL && screen != Screen.TRADING) {
                orderBookViewModel.stop();
                orderBookLadder = null;
            }
            refreshSubscriptionCount();
            if (screen == null) return;
            String location = switch (screen) {
                case STOCK_DETAIL -> "종목 상세 · " + session.selectedStock().name();
                case TRADING -> "주문 · " + session.selectedStock().name();
                case SIMILAR -> "닮은 차트 · " + session.selectedStock().name();
                case NEWS -> "뉴스 · " + session.selectedStock().name();
                default -> screen.label();
            };
            currentLocation.setText(location);
            currentLocation.setAccessibleText("현재 화면 " + location);
            Platform.runLater(() -> applyKeyboardGuidance(accessibility.keyboardGuidanceEnabled()));
        });
        screenController.register(Screen.DASHBOARD, this::createDashboard);
        screenController.register(Screen.CONNECTION, this::createConnectionScreen);
        screenController.register(Screen.MARKET, this::createMarketScreen);
        screenController.registerPreservingState(Screen.SEARCH, this::createSearchScreen);
        screenController.register(Screen.STOCK_DETAIL, this::createStockScreen);
        screenController.registerPreservingState(Screen.WATCHLIST, this::createWatchlistScreen);
        screenController.register(Screen.SCANNER, this::createScannerScreen);
        screenController.register(Screen.CONDITION, this::createConditionScreen);
        screenController.register(Screen.TRADING, this::createTradingScreen);
        screenController.register(Screen.ACCOUNT, this::createAccountScreen);
        screenController.register(Screen.US_MARKET,
                () -> new UsMarketScreenView(this::createUsWatchlistPanel).create());
        screenController.registerPreservingState(Screen.ANOMALY, this::createAnomalyScreen);
        screenController.registerPreservingState(Screen.NOTIFICATIONS,
                () -> new NotificationsScreenView(session.notifications(), status::setText,
                        this::scheduleStateSave, this::requestSpeech).create());
        screenController.register(Screen.SIMILAR,
                () -> withStockPicker(Screen.SIMILAR, createSimilarScreen()));
        screenController.register(Screen.NEWS,
                () -> withStockPicker(Screen.NEWS, createNewsScreen()));
        screenController.register(Screen.RADIO,
                () -> withStockPicker(Screen.RADIO, createAccessibleChartScreen()));
        screenController.registerPreservingState(Screen.SETTINGS, this::createSettingsScreen);
    }

    private VBox createDashboard() {
        Label loading = new Label("키움 모의계좌를 조회하고 있습니다.");
        ProgressIndicator progress = new ProgressIndicator();
        VBox host = new VBox(12, progress, loading);
        host.setAlignment(Pos.CENTER);
        host.getStyleClass().addAll("screen-content", "dashboard-screen");
        CompletableFuture.supplyAsync(tradingUseCase::account).whenComplete((snapshot, failure) ->
                Platform.runLater(() -> host.getChildren().setAll(
                        createDashboardContent(failure == null ? snapshot : null))));
        return host;
    }

    private VBox createDashboardContent(Account snapshot) {
        String accountStatus;
        String profitStatus;
        String orderableStatus;
        if (snapshot != null) {
            accountStatus = Formatters.won(snapshot.totalAssets());
            profitStatus = signedWon(snapshot.totalProfitLoss());
            orderableStatus = Formatters.won(snapshot.deposits().orderable());
        } else {
            accountStatus = "계좌 조회 필요";
            profitStatus = "연결 후 표시";
            orderableStatus = "연결 후 표시";
        }

        Label title = heading("오늘의 투자 홈");
        Label description = new Label(snapshot == null
                ? "키움 계좌를 연결하면 자산과 주문 가능 금액을 보여드립니다."
                : "키움 모의계좌 " + snapshot.maskedAccountNo());
        description.getStyleClass().add("muted-text");
        VBox intro = new VBox(3, title, description);
        Button listen = new Button("홈 요약 듣기");
        Account spokenSnapshot = snapshot;
        listen.setDisable(spokenSnapshot == null);
        listen.setOnAction(event -> requestSpeech(
                "총 자산 " + Formatters.won(spokenSnapshot.totalAssets())
                        + ", 평가손익은 " + signedWon(spokenSnapshot.totalProfitLoss())
                        + ", 주문 가능 금액은 " + Formatters.won(spokenSnapshot.deposits().orderable()) + " 입니다.",
                "dashboard-summary"));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, intro, spacer, listen); header.setAlignment(Pos.CENTER_LEFT);

        GridPane marketState = new GridPane();
        marketState.setHgap(10);
        marketState.getColumnConstraints().addAll(equalDashboardColumn(), equalDashboardColumn(), equalDashboardColumn());
        marketState.add(dashboardStatusCard("계좌 총자산", accountStatus, "키움 API 응답", "neutral"), 0, 0);
        marketState.add(dashboardStatusCard("평가손익", profitStatus, "보유 종목 기준",
                snapshot != null && snapshot.totalProfitLoss().signum() < 0 ? "negative" : "positive"), 1, 0);
        marketState.add(dashboardStatusCard("주문 가능 금액", orderableStatus, realtimeStatus.getText(), "connection"), 2, 0);

        GridPane features = new GridPane();
        features.setHgap(10); features.setVgap(10);
        features.getColumnConstraints().addAll(equalDashboardColumn(), equalDashboardColumn(), equalDashboardColumn());
        features.add(dashboardFeatureCard("종목 찾기", "종목명·코드 검색", Screen.SEARCH), 0, 0);
        features.add(dashboardFeatureCard("계좌", "자산·예수금·주문 현황", Screen.ACCOUNT), 1, 0);
        features.add(dashboardFeatureCard("이상 감지", "실시간 가격·거래량 신호", Screen.ANOMALY), 2, 0);
        features.add(dashboardFeatureCard("관심종목", "내 종목을 빠르게 확인", Screen.WATCHLIST), 0, 1);
        features.add(dashboardFeatureCard("청각 차트", "시세를 소리로 탐색", Screen.RADIO), 1, 1);
        features.add(dashboardFeatureCard("음성·화면 설정", "TTS·크기·고대비", Screen.SETTINGS), 2, 1);

        VBox dashboard = new VBox(12, header, sectionHeading("오늘의 계좌 상태"), marketState,
                sectionHeading("주요 기능"), features);
        dashboard.getStyleClass().add("dashboard-shell");
        dashboard.setMaxWidth(980);
        StackPane centered = new StackPane(dashboard);
        centered.setAlignment(Pos.TOP_CENTER);
        VBox body = new VBox(centered);
        body.getStyleClass().addAll("screen-content", "dashboard-screen");
        body.setPadding(new Insets(18));
        VBox.setVgrow(centered, Priority.ALWAYS);
        return body;
    }

    private ColumnConstraints equalDashboardColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(33.333);
        column.setHgrow(Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }

    private VBox dashboardStatusCard(String title, String value, String detail, String tone) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dashboard-card-title");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("dashboard-card-value");
        valueLabel.setWrapText(true);
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("muted-text");
        detailLabel.setWrapText(true);
        VBox card = new VBox(5, titleLabel, valueLabel, detailLabel);
        card.getStyleClass().addAll("dashboard-status-card", "dashboard-tone-" + tone);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private Button dashboardFeatureCard(String title, String detail, Screen screen) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dashboard-feature-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("dashboard-feature-detail");
        detailLabel.setWrapText(true);
        VBox copy = new VBox(3, titleLabel, detailLabel);
        copy.setAlignment(Pos.CENTER);
        VBox graphic = new VBox(7, navigationIcon(screen), copy);
        graphic.setAlignment(Pos.CENTER);
        Button button = new Button();
        button.setGraphic(graphic);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMaxHeight(Double.MAX_VALUE);
        button.getStyleClass().add("dashboard-feature-card");
        button.setAccessibleText(title + ". " + detail + ". 화면 열기");
        button.setOnAction(event -> openNavigationScreen(screen));
        return button;
    }

    private ScrollPane createConnectionScreen() {
        return new ConnectionScreenView(connectionViewModel, status::setText,
                tradingUseCase::account, realtimeStatus.textProperty(), marketDataSource).create();
    }

    private StackPane createAccountScreen() {
        Label loading = new Label("키움 모의계좌와 주문 내역을 조회하고 있습니다.");
        ProgressIndicator progress = new ProgressIndicator();
        VBox loadingBox = new VBox(12, progress, loading);
        loadingBox.setAlignment(Pos.CENTER);
        StackPane host = new StackPane(loadingBox);
        host.getStyleClass().add("screen-content");
        CompletableFuture.supplyAsync(() -> new AccountScreenData(
                tradingUseCase.account(), tradingUseCase.orders())).whenComplete((data, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        loading.setText("계좌를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                        progress.setVisible(false);
                    } else {
                        host.getChildren().setAll(new AccountScreenView(session::journalEntries,
                            table -> openSelectedStock(table, 0),
                            (table, orderSide) -> navigateForSelectedStock(table, 0, orderSide),
                            status::setText,
                            this::showJournalDialog,
                            this::deleteSelectedJournal).create(data));
                    }
                }));
        return host;
    }

    /**
     * 주문 화면.
     *
     * <p>창이 낮으면 아래쪽 주문 상태가 화면 밖으로 밀린다. 접수 결과를 확인하는 자리라
     * 가려지면 안 되므로 잘라 내지 않고 스크롤로 닿을 수 있게 감싼다.
     */
    private javafx.scene.Node createTradingScreen() {
        Label loading = new Label("키움 모의계좌 주문 상태를 조회하고 있습니다.");
        ProgressIndicator progress = new ProgressIndicator();
        VBox host = new VBox(12, progress, loading);
        host.setAlignment(Pos.CENTER);
        host.getStyleClass().addAll("screen-content", "trading-screen");
        CompletableFuture.supplyAsync(tradingUseCase::orders).whenComplete((orders, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        progress.setVisible(false);
                        loading.setText("주문 상태를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                    } else {
                        host.getChildren().setAll(createTradingScreenContent(orders));
                        host.setAlignment(Pos.TOP_LEFT);
                    }
                }));
        ScrollPane scroll = new ScrollPane(host);
        scroll.setFitToWidth(true);
        // 높이를 맞추면 내용이 늘어나지 못해 잘린다. 넘치면 스크롤되게 둔다.
        scroll.setFitToHeight(false);
        scroll.setAccessibleText("주문 화면");
        scroll.getStyleClass().add("workspace-scroll");
        return scroll;
    }

    private VBox createTradingScreenContent(List<Order> orders) {
        StockDetail selectedDetail = stockDetailViewModel.detail();
        Label title = heading("주문");
        Label notice = new Label("확인하면 키움 모의투자 서버로 주문이 전송됩니다. 실제 현금이 오가는 실전 주문은 지원하지 않습니다.");
        notice.getStyleClass().add("safety-note"); notice.setWrapText(true);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, titleSpacer, notice);
        header.setAlignment(Pos.CENTER_LEFT);
        notice.setMaxWidth(720);

        Node orderBook = createOrderBookPanel(selectedDetail.name());
        VBox orderForm = createOrderForm();
        SplitPane orderArea = new SplitPane(orderBook, orderForm);
        orderArea.setDividerPositions(0.46);
        orderArea.setMinHeight(0);
        orderArea.getStyleClass().add("trading-order-area");
        orderForm.setMinWidth(0);

        TableView<ObservableList<String>> openOrders = orderStatusTable(true, orders);
        TableView<ObservableList<String>> fills = orderStatusTable(false, orders);
        Button cancel = new Button("선택 주문 취소"); cancel.setOnAction(event -> cancelSelectedOrder(openOrders));
        Button cancelAll = new Button("미체결 전량 취소"); cancelAll.setOnAction(event -> cancelAllOrders(openOrders));
        FlowPane orderActions = wrappingRow(8, cancel, cancelAll);
        VBox openContent = new VBox(10, stateBanner("재연결 후 주문 상태를 확인했습니다.", "success"), openOrders, orderActions);
        VBox.setVgrow(openOrders, Priority.ALWAYS);
        TabPane statusTabs = new TabPane(tab("미체결", openContent), tab("체결", fills));
        statusTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        statusTabs.setMinHeight(120);
        statusTabs.setPrefHeight(165);
        statusTabs.setMaxHeight(190);
        statusTabs.getStyleClass().add("order-status-tabs");

        VBox body = new VBox(10, header, orderArea, sectionHeading("주문 상태"), statusTabs);
        body.getStyleClass().addAll("screen-content", "trading-screen");
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0);
        VBox.setVgrow(orderArea, Priority.ALWAYS);
        return body;
    }

    private VBox createStockScreen() {
        StockDetail detail = stockDetailViewModel.detail();
        var selection = stockDetailViewModel.selection();
        pendingOrderPrice = stockDetailViewModel.plainOrderPrice();
        Label title = heading(detail.name());
        title.getStyleClass().add("stock-detail-title");
        Label symbol = new Label(detail.symbol() + " · " + selection.exchange());
        symbol.getStyleClass().addAll("mode-badge", "stock-detail-symbol");
        // 담긴 뒤에도 "추가" 라고 적혀 있으면 눌린 것인지 알 수 없다. 단추가 상태를 든다.
        Button favorite = new WatchlistToggle(session.watchlistItems(), detail.symbol(),
                selection.exchange(), detail.name(),
                () -> addToWatchlistBySymbol(detail.symbol(), detail.name()),
                () -> {
                    stockSearchViewModel.removeFromWatchlist(detail.symbol(), selection.exchange());
                    scheduleStateSave();
                },
                status::setText).button();
        favorite.getStyleClass().add("stock-compact-action");
        Button buy = primaryButton("매수", () -> openOrder(OrderSide.BUY));
        buy.getStyleClass().add("stock-compact-action");
        Button sell = new Button("매도"); sell.getStyleClass().addAll("sell-button", "stock-compact-action"); sell.setOnAction(event -> openOrder(OrderSide.SELL));
        Region titleSpacer = new Region(); HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(8, title, symbol, titleSpacer, favorite, sell, buy); titleRow.setAlignment(Pos.CENTER_LEFT);
        String direction = detail.direction() == PriceDirection.UP ? "상승" : detail.direction() == PriceDirection.DOWN ? "하락" : "보합";
        Label price = new Label(stockDetailViewModel.formatPrice(detail.currentPrice()) + " · " + direction + " "
                + stockDetailViewModel.formatPrice(detail.changeAmount().abs()) + " · " + detail.changeRate().abs() + "%");
        price.getStyleClass().add("stock-price");
        Button listen = new Button("최신 정보 듣기");
        listen.getStyleClass().add("stock-compact-action");
        listen.setOnAction(event -> requestSpeech(detail.name() + " 현재가 "
                + stockDetailViewModel.formatPrice(detail.currentPrice())
                + ", 전일 대비 " + direction + " " + detail.changeRate().abs() + "퍼센트입니다.",
                "stock-detail-" + detail.symbol()));

        VBox openMetric = miniMetric("시가", stockDetailViewModel.formatPrice(detail.open()));
        VBox highMetric = miniMetric("고가", stockDetailViewModel.formatPrice(detail.high()));
        VBox lowMetric = miniMetric("저가", stockDetailViewModel.formatPrice(detail.low()));
        // 시가총액과 외국인 소진률은 ka10001 이 함께 주지만 아직 도메인 모델에 담지
        // 않았다. 값을 지어내지 않고 항목 자체를 빼 둔다.
        VBox volumeMetric = miniMetric("거래량", String.format("%,d", detail.volume()));
        List.of(openMetric, highMetric, lowMetric, volumeMetric).forEach(metric -> metric.setPrefWidth(108));
        FlowPane quoteRow = wrappingRow(8, price, listen, openMetric, highMetric, lowMetric, volumeMetric);
        quoteRow.getStyleClass().add("stock-quote-row");

        HBox periods = new HBox(5);
        periods.setAlignment(Pos.CENTER_LEFT);
        periods.getStyleClass().add("stock-periods");
        ToggleGroup periodGroup = new ToggleGroup();
        Map<ToggleButton, StockDetailViewModel.ChartRange> periodButtons = new java.util.LinkedHashMap<>();
        for (StockDetailViewModel.ChartRange range : StockDetailViewModel.ChartRange.values()) {
            ToggleButton button = new ToggleButton(range.label()); button.setToggleGroup(periodGroup);
            button.getStyleClass().add("stock-chart-toggle");
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
        HBox indicators = new HBox(8, movingAverage, bollinger, rsi, macd);
        indicators.setAlignment(Pos.CENTER_LEFT);
        indicators.getStyleClass().add("stock-indicators");

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
                    startLiveChart(candleChart, history);
                    status.setText(detail.name() + " " + range.label() + " 차트로 변경했습니다.");
                }
            });
        }));
        Button soundChart = new Button("이 차트를 소리로 탐색");
        soundChart.getStyleClass().add("stock-compact-action");
        soundChart.setOnAction(event -> navigate(Screen.RADIO));
        FlowPane chartToolbar = new FlowPane(10, 6, periods, indicators, soundChart);
        chartToolbar.setAlignment(Pos.CENTER_LEFT);
        chartToolbar.setPrefWrapLength(1060);
        chartToolbar.getStyleClass().add("stock-chart-toolbar");
        TabPane chartRepresentations = new TabPane(tab("그래프", candleChart), tab("접근 가능한 표", history));
        chartRepresentations.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        chartRepresentations.setMinHeight(0); chartRepresentations.setPrefHeight(440);
        VBox chart = new VBox(7, chartToolbar, chartRepresentations); chart.setPadding(new Insets(6));
        chart.setMinHeight(0); VBox.setVgrow(chartRepresentations, Priority.ALWAYS);
        startLiveChart(candleChart, history);

        // 호가와 체결은 키움 조회 뒤 실시간 스트림으로 이어 붙인다. 기업정보·거래원·
        // 프로그램매매는 아직 실제 TR이 없으므로 완성된 기능처럼 탭을 노출하지 않는다.
        javafx.scene.Node orderBook = createOrderBookPanel(detail.name());
        javafx.scene.Node trades = createTradeTapePanel(detail.name());

        TabPane tabs = new TabPane(tab("차트", chart), tab("호가", orderBook), tab("체결", trades));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(0); tabs.setPrefHeight(540); tabs.setMaxHeight(Double.MAX_VALUE);
        tabs.getStyleClass().add("stock-detail-tabs");
        VBox summary = new VBox(8, titleRow, quoteRow);
        summary.getStyleClass().addAll("panel-card", "stock-detail-summary");
        VBox body = new VBox(12, summary, tabs);
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0); body.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        body.getStyleClass().add("screen-content");
        body.setAccessibleText("종목 상세 " + detail.name());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return body;
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

    private VBox createSearchScreen() {
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

    /** 미국주식 관심종목 패널. 사용자의 기록이라 실제 값을 보여 준다. */
    private VBox createUsWatchlistPanel() {
        // 다만 미국주식 주문은 아직 연동하지 않았으므로 주문할 수 있는 것처럼 보이게 하지 않는다.
        return new WatchlistScreenView(watchlistViewModel, this::openWatchlistStock,
                () -> startWatchlistSearch("미국"), status::setText)
                .createUsPanel(selected -> status.setText(
                        "미국주식 주문은 아직 연동되지 않았습니다. 연동 예정: ust20000 매수, ust20001 매도"));
    }

    /**
     * 분석 화면 위에 종목 고르개를 얹는다.
     *
     * <p>청각 차트와 닮은 차트와 뉴스는 모두 고른 종목 하나를 본다. 그런데 그 값을 바꾸는
     * 길이 검색뿐이라, 종목 하나 바꾸는 데 화면을 두 번 옮겨야 했다. 화면에 고르개가 없어
     * 지금 어느 종목을 보고 있는지도 제목으로만 알 수 있었다.
     *
     * <p>고르면 전역 선택 종목을 바꾼다. 화면마다 다른 종목을 들고 있으면 상세와 주문이
     * 무엇을 가리키는지 알 수 없다. 주문은 제출 전에 종목 코드를 다시 보여 주므로, 여기서
     * 바꾼 것이 곧바로 주문으로 이어지지 않는다.
     */
    private javafx.scene.Node withStockPicker(Screen screen, javafx.scene.Node content) {
        StockPicker picker = new StockPicker(session.watchlistItems(), session.selectedStock(),
                selected -> {
                    session.selectStock(selected);
                    // 고르개에 초점을 돌려 둔다. 화면을 다시 만들면 초점이 처음으로 가는데,
                    // 종목을 여럿 견주는 동안 매번 탭으로 돌아오게 하면 못 쓴다.
                    pickerFocusScreen = screen;
                    screenController.invalidate(screen);
                    screenController.show(screen);
                });
        loadHoldingsInto(picker);

        VBox host = new VBox(10, picker.root(), content);
        host.setFillWidth(true);
        VBox.setVgrow(content, Priority.ALWAYS);
        if (pickerFocusScreen == screen) {
            pickerFocusScreen = null;
            Platform.runLater(picker.root()::requestFocus);
        }
        return host;
    }

    /** 보유 종목은 계좌 조회가 끝나야 안다. 화면 스레드를 막지 않는다. */
    private void loadHoldingsInto(StockPicker picker) {
        CompletableFuture.supplyAsync(tradingUseCase::account)
                .whenComplete((account, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        return;
                    }
                    List<StockSelection> held = new java.util.ArrayList<>();
                    for (Position position : account.positions()) {
                        held.add(new StockSelection("국내", position.symbol(), position.name(),
                                "KRX", "KRW"));
                    }
                    picker.setHoldings(held);
                }));
    }

    /**
     * 닮은 차트 화면.
     *
     * <p>이 화면이 하는 말은 하나다 — 과거 어느 구간이 지금과 모양이 닮았다. 예측이
     * 아니다. 그래서 단서를 목록보다 먼저 읽히는 자리에 둔다.
     */
    private javafx.scene.Node createSimilarScreen() {
        StockSelection selected = session.selectedStock();
        similarView = new SimilarScreenView(selected.name(),
                this::requestSpeech,
                this::watchlistToggleFor,
                (symbol, name) -> compareWithSelected(selected, symbol, name),
                ignored -> loadSimilar());
        javafx.scene.Node node = similarView.create();
        loadSimilar();
        return node;
    }

    private void loadSimilar() {
        SimilarScreenView view = similarView;
        if (view == null) {
            return;
        }
        if (!aiInsightViewModel.available()) {
            // 서버는 모델을 읽고 지수를 받은 뒤에야 포트를 연다. 그 사이의 연결 거부를
            // 실패로 적으면 사용자는 고칠 수 없는 문제로 읽고 포기한다.
            view.unavailable(aiServiceProcess != null && aiServiceProcess.running()
                    ? "AI 서버를 준비하고 있습니다. 10초쯤 걸립니다."
                    : aiInsightViewModel.unavailableReason());
            return;
        }
        view.loading();
        aiInsightViewModel.analyze(session.selectedStock().securityId(), true,
                insight -> {
                    lastInsight = insight;
                    view.show(insight);
                },
                view::unavailable);
    }

    /**
     * 관심종목 담기·빼기 단추를 만든다.
     *
     * <p>화면마다 담긴 상태를 따로 세면 한 곳이 어긋난다. 단추가 목록을 직접 지켜보므로
     * 어느 화면에서 지워도 함께 바뀐다.
     */
    private Button watchlistToggleFor(String symbol, String name) {
        return new WatchlistToggle(session.watchlistItems(), symbol, "", name,
                () -> addToWatchlistBySymbol(symbol, name),
                () -> {
                    stockSearchViewModel.removeFromWatchlist(symbol, "");
                    scheduleStateSave();
                },
                status::setText).button();
    }

    /**
     * 종목 코드로 관심 목록에 담는다.
     *
     * <p>조회로 식별 정보를 채운 뒤 담는다. 코드와 이름만으로 만들면 시장과 통화를
     * 추측하게 되고, 미국 종목을 국내로 담아 버린다.
     *
     * <p>조회는 시간이 걸린다. 결과를 기다리지 않고 참을 돌려주면 단추가 담긴 것처럼
     * 바뀌었다가 되돌아간다. 그래서 여기서 기다린다.
     */
    private boolean addToWatchlistBySymbol(String symbol, String name) {
        status.setText(name + " 종목을 확인하고 있습니다.");
        try {
            StockSearchItem item = stockSearchViewModel.findBestMatch(symbol)
                    .toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (item == null) {
                status.setText(name + " 종목 정보를 찾지 못했습니다.");
                return false;
            }
            boolean added = stockSearchViewModel.addToWatchlist(item);
            if (added) {
                scheduleStateSave();
            }
            return added;
        } catch (java.util.concurrent.TimeoutException timeout) {
            status.setText(name + " 종목 정보를 받는 데 시간이 걸립니다. 잠시 뒤 다시 시도해주세요.");
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            status.setText(name + " 종목 정보를 받지 못했습니다.");
            return false;
        }
    }

    /**
     * 두 종목을 나란히 보여 준다.
     *
     * <p>봉을 둘 다 받고 나서 연다. 하나만 받고 열면 빈 칸이 0원으로 읽힌다.
     */
    private void compareWithSelected(StockSelection selected, String symbol, String name) {
        status.setText(selected.name() + "과 " + name + " 시세를 조회하고 있습니다.");
        SecurityId other = SecurityId.of(symbol, "KRX");
        marketApplication.loadCandles(selected.securityId(), CandleInterval.DAY, 20)
                .thenCombine(marketApplication.loadCandles(other, CandleInterval.DAY, 20),
                        java.util.Map::entry)
                .whenComplete((pair, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        status.setText(name + " 시세를 받지 못해 비교할 수 없습니다.");
                        return;
                    }
                    status.setText(selected.name() + "과 " + name + "을 비교합니다.");
                    StockComparisonDialog.show(selected.name(), pair.getKey(),
                            name, pair.getValue(),
                            similarFieldOf(symbol, SimilarStock::similarityPercent,
                                    java.math.BigDecimal.ZERO),
                            similarFieldOf(symbol, SimilarStock::explanation, ""),
                            () -> addToWatchlistBySymbol(symbol, name));
                }));
    }

    /** 방금 받은 분석에서 그 종목의 값을 꺼낸다. 없으면 지어내지 않고 기본값을 쓴다. */
    private <T> T similarFieldOf(String symbol,
                                 java.util.function.Function<SimilarStock, T> field, T fallback) {
        if (lastInsight == null) {
            return fallback;
        }
        return lastInsight.similar().stream()
                .filter(stock -> stock.symbol().equalsIgnoreCase(symbol))
                .findFirst().map(field).orElse(fallback);
    }

    /**
     * 뉴스와 챗봇 화면.
     *
     * <p>챗봇에는 화면이 이미 보여 주고 있는 분석을 함께 넘긴다. 서버가 다시 계산하면
     * 그새 값이 바뀌어 사용자가 보고 있는 것과 다른 답을 듣는다.
     */
    private javafx.scene.Node createNewsScreen() {
        StockSelection selected = session.selectedStock();
        newsView = new NewsScreenView(selected.name(), this::requestSpeech,
                (question, onAnswer) -> newsViewModel.ask(
                        selected.securityId(), question, lastInsight, onAnswer),
                this::loadNews);
        javafx.scene.Node node = newsView.create();
        loadNews();
        // 챗봇이 분석을 근거로 답할 수 있게 미리 받아 둔다. 실패해도 뉴스는 그대로 나온다.
        if (lastInsight == null && aiInsightViewModel.available()) {
            aiInsightViewModel.analyze(selected.securityId(), false,
                    insight -> lastInsight = insight, reason -> { });
        }
        return node;
    }

    private void loadNews() {
        NewsScreenView view = newsView;
        if (view == null) {
            return;
        }
        view.loading();
        newsViewModel.load(session.selectedStock().securityId(), view::show, view::unavailable);
    }

    private javafx.scene.Node createAnomalyScreen() {
        Label title = heading("이상 감지");
        Label monitoring = new Label(anomalySubscriptions.isEmpty()
                ? "보유종목이나 관심종목이 있으면 실시간 감시를 시작합니다."
                : "보유·관심종목 " + anomalySubscriptions.size() + "개를 실시간 감시 중입니다.");
        monitoring.getStyleClass().add("muted-text");
        VBox titleBlock = new VBox(2, title, monitoring);
        Button manageWatchlist = new Button("감시 종목 관리");
        manageWatchlist.setOnAction(event -> navigate(Screen.WATCHLIST));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, titleBlock, spacer, manageWatchlist);
        header.setAlignment(Pos.CENTER_LEFT);

        FilteredList<String> signals = new FilteredList<>(session.notifications(),
                value -> value.contains("· 이상 감지 ·"));
        StackPane urgentHost = new StackPane();
        Runnable renderUrgent = () -> urgentHost.getChildren().setAll(
                signals.isEmpty() ? anomalyEmptyUrgentCard() : anomalyUrgentCard(signals.get(0)));
        signals.addListener((javafx.collections.ListChangeListener<String>) change -> renderUrgent.run());
        renderUrgent.run();

        java.util.Set<String> holdingNames = new java.util.HashSet<>();
        FilteredList<String> holdingSignals = new FilteredList<>(signals,
                value -> holdingNames.stream().anyMatch(value::contains));
        FilteredList<String> watchlistSignals = new FilteredList<>(signals,
                value -> holdingNames.stream().noneMatch(value::contains));
        CompletableFuture.supplyAsync(tradingUseCase::account).whenComplete((account, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) return;
                    holdingNames.clear();
                    account.positions().forEach(position -> holdingNames.add(position.name()));
                    holdingSignals.setPredicate(value -> holdingNames.stream().anyMatch(value::contains));
                    watchlistSignals.setPredicate(value -> holdingNames.stream().noneMatch(value::contains));
                }));

        ListView<String> holdings = anomalySignalList(holdingSignals, "보유 종목 이상 신호가 없습니다.");
        ListView<String> watchlist = anomalySignalList(watchlistSignals, "관심 종목 이상 신호가 없습니다.");
        holdings.setPrefHeight(150);
        watchlist.setPrefHeight(190);

        Button listen = new Button("선택 신호 듣기");
        listen.setOnAction(event -> {
            String selected = holdings.getSelectionModel().getSelectedItem();
            if (selected == null) selected = watchlist.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("들을 이상 감지 신호를 선택해주세요.");
                return;
            }
            requestSpeech(selected, "anomaly-selected");
        });
        Button delete = new Button("선택 신호 지우기");
        delete.getStyleClass().add("danger-outline-button");
        delete.setOnAction(event -> {
            String selected = holdings.getSelectionModel().getSelectedItem();
            if (selected == null) selected = watchlist.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.setText("지울 이상 감지 신호를 선택해주세요.");
                return;
            }
            session.notifications().remove(selected);
            scheduleStateSave();
            status.setText("선택한 이상 감지 신호를 지웠습니다.");
        });
        holdings.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) watchlist.getSelectionModel().clearSelection();
        });
        watchlist.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) holdings.getSelectionModel().clearSelection();
        });

        VBox criteria = new VBox(5,
                informationRow("가격 급변", anomalyConfig.window().toMinutes() + "분 동안 "
                        + anomalyConfig.priceThresholdPercent().stripTrailingZeros() + "% 이상"),
                informationRow("거래량 급증", anomalyConfig.baselineWindow().toMinutes() + "분 기준 대비 "
                        + anomalyConfig.volumeThresholdRatio().stripTrailingZeros() + "배 이상"),
                informationRow("반복 제한", "같은 종목·유형은 " + anomalyConfig.cooldown().toMinutes() + "분 동안 다시 알리지 않음"));
        TitledPane criteriaPane = new TitledPane("자동 이상 감지 기준 보기", criteria);
        criteriaPane.setExpanded(false);

        VBox shell = new VBox(9, header, urgentHost, createAiInsightPanel(),
                anomalySection("보유 종목 알림", holdings),
                anomalySection("관심 종목 알림", watchlist),
                wrappingRow(8, listen, delete), criteriaPane);
        shell.getStyleClass().addAll("anomaly-shell", "settings-shell");
        shell.setMaxWidth(1040);
        StackPane centered = new StackPane(shell);
        centered.setAlignment(Pos.TOP_CENTER);
        VBox body = new VBox(centered);
        body.getStyleClass().addAll("screen-content", "anomaly-screen");
        body.setPadding(new Insets(12));
        VBox.setVgrow(centered, Priority.ALWAYS);
        // 내용이 창보다 길어지면 BorderPane 은 넘친 만큼 위쪽 막대를 덮는다. 잘라 내지 않고
        // 스크롤로 닿게 한다. AI 분석이 여러 줄로 접히면 화면이 쉽게 길어진다.
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setAccessibleText("이상 감지 화면");
        scroll.getStyleClass().add("workspace-scroll");
        return scroll;
    }

    /**
     * 보유·관심 종목의 AI 분석.
     *
     * <p>이 화면은 종목 하나를 들여다보는 곳이 아니라 여러 종목을 훑는 곳이다. 한 종목짜리
     * 카드를 맨 위에 두면 아래 목록과 아무 관계 없는 정보가 제일 먼저 읽힌다. 게다가 이
     * 화면에는 종목 선택기가 없어서 사용자는 문안을 끝까지 들어야 어느 종목인지 알 수 있다.
     *
     * <p>닮은 종목은 끄고 부른다. 종목당 몇 초가 더 드는데 목록에서 쓸 정보가 아니다.
     * 그건 닮은 차트 화면에서 종목 하나를 골라 볼 때 켠다.
     */
    private javafx.scene.Node createAiInsightPanel() {
        aiInsightListPanel = new AiInsightListPanel(text -> requestSpeech(text, "ai-insight"));
        loadAiInsightList();
        return aiInsightListPanel.root();
    }

    private void loadAiInsightList() {
        AiInsightListPanel panel = aiInsightListPanel;
        if (panel == null) {
            return;
        }
        if (!aiInsightViewModel.available()) {
            // 서버는 모델을 읽고 지수를 받은 뒤에야 포트를 연다. 그 사이의 연결 거부를
            // 실패로 적으면 사용자는 고칠 수 없는 문제로 읽고 포기한다.
            panel.unavailable(aiServiceProcess != null && aiServiceProcess.running()
                    ? "AI 서버를 준비하고 있습니다. 10초쯤 걸립니다."
                    : aiInsightViewModel.unavailableReason(), this::loadAiInsightList);
            return;
        }
        panel.waiting();
        // 계좌 조회가 끝나야 보유 종목을 안다. 화면 스레드를 막지 않는다.
        CompletableFuture.supplyAsync(tradingUseCase::account)
                .handle((account, failure) -> failure == null ? account : null)
                .thenAccept(account -> Platform.runLater(() -> startAiInsightList(panel, account)));
    }

    /**
     * 감시 중인 종목을 모아 차례로 분석한다.
     *
     * <p>보유 종목을 먼저, 관심 종목을 뒤에 둔다. 돈이 들어가 있는 쪽이 먼저 읽혀야 한다.
     * 같은 종목이 양쪽에 있으면 한 번만 넣는다.
     */
    private void startAiInsightList(AiInsightListPanel panel, Account account) {
        // 거래소를 함께 들고 다닌다. 종목 코드만 남기고 KRX 로 다시 만들면, 관심 목록에
        // 담아 둔 미국 종목이 같은 코드의 국내 종목으로 조회된다.
        Map<SecurityId, String> names = new LinkedHashMap<>();
        if (account != null) {
            for (Position position : account.positions()) {
                names.putIfAbsent(SecurityId.of(position.symbol(), "KRX"), position.name());
            }
        }
        for (WatchlistItem item : session.watchlistItems()) {
            if (!item.needsIdentityRepair()) {
                names.putIfAbsent(item.securityId(), item.securityName());
            }
        }
        if (names.isEmpty()) {
            panel.empty("보유 종목이나 관심 종목을 추가하면 AI 분석을 함께 보여 드립니다.");
            return;
        }

        List<SecurityId> securities = List.copyOf(names.keySet());
        List<String> symbols = new java.util.ArrayList<>();
        for (SecurityId security : securities) {
            symbols.add(security.symbol());
        }
        panel.starting(List.copyOf(symbols), List.copyOf(names.values()));
        aiInsightViewModel.analyzeAll(securities, false,
                (security, insight) -> panel.show(security.symbol(), insight),
                (security, reason) -> panel.failed(security.symbol(), reason),
                panel::finished);
    }

    private ListView<String> anomalySignalList(FilteredList<String> items, String emptyText) {
        ListView<String> list = new ListView<>(items);
        list.getStyleClass().add("anomaly-signal-list");
        list.setCellFactory(ignored -> anomalySignalCell());
        list.setPlaceholder(new Label(emptyText));
        list.setAccessibleText(emptyText.replace("없습니다.", "목록"));
        return list;
    }

    private ListCell<String> anomalySignalCell() {
        return new ListCell<>() {
            private final Label name = new Label();
            private final Label time = new Label();
            private final Label message = new Label();
            private final Label badge = new Label();
            private final Region spacer = new Region();
            private final HBox top = new HBox(8, name, spacer, time);
            private final VBox card = new VBox(5, top, message, badge);
            {
                getStyleClass().add("anomaly-signal-cell");
                name.getStyleClass().add("anomaly-signal-name");
                time.getStyleClass().add("anomaly-signal-time");
                message.getStyleClass().add("anomaly-signal-message");
                badge.getStyleClass().add("anomaly-signal-badge");
                message.setWrapText(true);
                top.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                card.getStyleClass().add("anomaly-signal-card");
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String content = anomalyMessage(item);
                name.setText(anomalySecurityName(content));
                time.setText(anomalyTime(item));
                message.setText(content.replaceFirst("^(높음|주의) · ", ""));
                String kind = content.contains("거래량") ? "거래량 급증"
                        : content.contains("내렸") ? "가격 급락" : "가격 급등";
                badge.setText(kind);
                badge.getStyleClass().removeAll("badge-price-up", "badge-price-down", "badge-volume");
                badge.getStyleClass().add(content.contains("거래량") ? "badge-volume"
                        : content.contains("내렸") ? "badge-price-down" : "badge-price-up");
                setAccessibleText(name.getText() + ". " + kind + ". " + message.getText() + ". " + time.getText());
                setText(null);
                setGraphic(card);
            }
        };
    }

    private VBox anomalySection(String title, ListView<String> list) {
        Label label = new Label(title);
        label.getStyleClass().add("anomaly-section-title");
        return new VBox(5, label, list);
    }

    private VBox anomalyUrgentCard(String signal) {
        String content = anomalyMessage(signal);
        Label eyebrow = new Label("최근 이상 신호");
        Label message = new Label(content.replaceFirst("^(높음|주의) · ", ""));
        message.setWrapText(true);
        message.getStyleClass().add("anomaly-urgent-message");
        Label time = new Label(anomalyTime(signal));
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, eyebrow, spacer, time);
        Button listen = new Button("소리로 듣기");
        listen.getStyleClass().add("primary-button");
        listen.setOnAction(event -> requestSpeech(signal, "anomaly-latest"));
        Button details = new Button("자세히");
        details.setOnAction(event -> showInformation("이상 감지 상세", content));
        VBox card = new VBox(6, top, message, wrappingRow(7, listen, details));
        card.getStyleClass().add(content.startsWith("높음") ? "anomaly-urgent-high" : "anomaly-urgent-card");
        return card;
    }

    private VBox anomalyEmptyUrgentCard() {
        Label title = new Label("현재 긴급 이상 신호가 없습니다.");
        title.getStyleClass().add("anomaly-urgent-message");
        Label detail = new Label(anomalySubscriptions.isEmpty()
                ? "관심종목을 추가하면 자동 감지를 시작합니다."
                : "실시간 시세를 감시하고 있습니다.");
        detail.getStyleClass().add("muted-text");
        VBox card = new VBox(5, title, detail);
        card.getStyleClass().add("anomaly-urgent-card");
        return card;
    }

    private static String anomalyMessage(String notification) {
        String normalized = notification.replaceFirst("^새 알림 · ", "");
        int category = normalized.indexOf(" · 이상 감지 · ");
        return category < 0 ? normalized : normalized.substring(category + " · 이상 감지 · ".length());
    }

    private static String anomalyTime(String notification) {
        String normalized = notification.replaceFirst("^새 알림 · ", "");
        int separator = normalized.indexOf(" · ");
        return separator < 0 ? "" : normalized.substring(0, separator);
    }

    private static String anomalySecurityName(String content) {
        String plain = content.replaceFirst("^(높음|주의) · ", "");
        int recent = plain.indexOf(" 최근 ");
        return recent > 0 ? plain.substring(0, recent) : "이상 신호";
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
        GridPane form = new GridPane(); form.setHgap(8); form.setVgap(6);
        addCompactOrderField(form, 0, 0, "종목 코드", symbol);
        addCompactOrderField(form, 2, 0, "종목명", name);
        addCompactOrderField(form, 0, 1, "매수 / 매도", side);
        addCompactOrderField(form, 2, 1, "주문 유형", orderType);
        addCompactOrderField(form, 0, 2, "가격", price);
        addCompactOrderField(form, 2, 2, "수량", quantity);

        javafx.beans.property.ObjectProperty<Account> orderAccount =
                new javafx.beans.property.SimpleObjectProperty<>();
        Label availableAmount = new Label("모의계좌 조회 중");
        List<Button> ratioButtons = new java.util.ArrayList<>();
        HBox ratios = new HBox(8);
        for (int ratio : List.of(10, 25, 50, 100)) {
            Button button = new Button(ratio + "%");
            button.setDisable(true);
            button.setOnAction(event -> {
                Account account = orderAccount.get();
                if (account == null) return;
                long maximum;
                if (side.getValue() == OrderSide.SELL) {
                    maximum = account.position(symbol.getText())
                            .map(Position::availableQuantity).orElse(0L);
                } else {
                    try {
                        BigDecimal unitPrice = orderType.getValue() == OrderType.MARKET
                                ? stockDetailViewModel.detail().currentPrice()
                                : new BigDecimal(price.getText().replace(",", "").trim());
                        maximum = unitPrice.signum() <= 0 ? 0L
                                : account.deposits().orderable().divideToIntegralValue(unitPrice).longValue();
                    } catch (RuntimeException invalidPrice) {
                        maximum = 0L;
                    }
                }
                if (maximum < 1) {
                    status.setText(side.getValue() == OrderSide.SELL
                            ? "매도 가능한 보유수량이 없습니다."
                            : "현재 가격으로 주문할 수 있는 수량이 없습니다.");
                    return;
                }
                long calculated = Math.max(1L, maximum * ratio / 100L);
                quantity.getValueFactory().setValue((int) Math.min(1_000_000L, calculated));
            });
            ratioButtons.add(button);
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
        VBox estimates = new VBox(4,
                informationRow("주문 예상금액", estimatedAmount),
                informationRow("주문 가능금액", availableAmount));
        estimates.getStyleClass().add("estimate-box"); estimates.setPadding(new Insets(8));
        Button preview = new Button("주문 내용 검토"); preview.getStyleClass().add("primary-button"); preview.setDefaultButton(true);
        preview.setAccessibleHelp("주문을 제출하지 않고 재확인 창을 엽니다.");
        preview.setOnAction(event -> {
            if (preventDuplicateOrders) {
                preview.setDisable(true);
                PauseTransition unlock = new PauseTransition(Duration.millis(900));
                unlock.setOnFinished(done -> preview.setDisable(false));
                unlock.play();
            }
            previewOrder(symbol, name, side, orderType, quantity, price);
        });
        Label ratioLabel = new Label("주문 비율");
        HBox ratioRow = new HBox(10, ratioLabel, ratios);
        ratioRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, sectionHeading("모의주문 준비"), form, ratioRow, estimates, preview);
        box.getStyleClass().addAll("panel-card", "order-form-compact");
        box.setPadding(new Insets(12));
        box.setMaxHeight(Double.MAX_VALUE);
        CompletableFuture.supplyAsync(tradingUseCase::account).whenComplete((account, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        availableAmount.setText("계좌 조회 실패");
                        ratioButtons.forEach(button -> button.setDisable(true));
                        return;
                    }
                    orderAccount.set(account);
                    availableAmount.setText(Formatters.won(account.deposits().orderable()));
                    ratioButtons.forEach(button -> button.setDisable(false));
                }));
        return box;
    }

    private void addCompactOrderField(GridPane grid, int labelColumn, int row,
                                      String labelText, Control control) {
        Label label = new Label(labelText);
        label.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        grid.add(label, labelColumn, row);
        grid.add(control, labelColumn + 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
    }

    private void updateOrderDraft(OrderDraft initial, OrderSide side, OrderType type,
                                  Integer quantity, String price) {
        if (side == null || type == null || quantity == null || quantity <= 0
                || price == null || price.isBlank()) return;
        orderDraft = new OrderDraft(initial.symbol(), initial.name(), side, type, quantity, price, initial.origin());
        pendingOrderPrice = orderDraft.price();
    }

    /**
     * 설정 화면.
     *
     * <p>화면은 값을 바꿔 돌려주기만 한다. 합성기에 적용하는 일도, 저장하는 일도 여기서
     * 맡는다. 화면이 직접 합성기를 만지고 저장은 다른 곳에서 하면 한쪽만 도는 경우가 생긴다.
     */
    private VBox createSettingsScreen() {
        SettingsScreenView.Context context = new SettingsScreenView.Context(
                marketDataSource,
                secretStore.protectionLevel().displayName(),
                realtimeStatus.textProperty(),
                subscriptionCount.textProperty(),
                () -> tradingUseCase.account().maskedAccountNo(),
                this::availableSpeechVoices);
        SettingsScreenView.Actions actions = new SettingsScreenView.Actions(
                this::applyAccessibility,
                value -> {
                    preventDuplicateOrders = value;
                    scheduleStateSave();
                },
                this::previewSpeechSettings,
                this::auditCurrentScreen,
                this::navigate,
                status::setText);
        return new SettingsScreenView(accessibility, preventDuplicateOrders, context, actions)
                .create();
    }

    /**
     * 미리 듣기.
     *
     * <p>음성이 꺼져 있어도 들려준다. 끈 채로 설정을 만지는 동안 무엇이 바뀌는지 확인할
     * 길이 없으면 설정 자체를 쓸 수 없다. 잠깐 켰다가 원래대로 돌린다.
     */
    private void previewSpeechSettings(String text) {
        AccessibilityPreferences before = accessibility;
        accessibility = accessibility.withSpeechEnabled(true);
        announce(text, SpeechPriority.USER_REQUEST, "settings-preview");
        accessibility = before;
    }

    /** 지금 만들어져 있는 화면에서 접근 가능한 이름이 빠진 곳을 찾는다. */
    private void auditCurrentScreen() {
        List<AccessibilityAudit.Issue> issues = new AccessibilityAudit().audit(root);
        if (issues.isEmpty()) {
            showInformation("접근성 검사 통과", "현재 생성된 화면에서 접근 가능한 이름 누락을 찾지 못했습니다.");
            return;
        }
        String details = issues.stream().limit(8).map(AccessibilityAudit.Issue::message)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        showInformation("접근성 검사 결과 " + issues.size() + "건", details);
    }

    /** 합성기가 아는 음성 목록. 합성기가 목록을 못 주면 빈 목록이다. */
    private List<SpeechVoice> availableSpeechVoices() {
        return speechPort instanceof SpeechVoiceProvider provider
                ? provider.availableVoices() : List.of();
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
    /** 주문 접수 시각을 화면 표기로 바꾼다. */

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
            status.setText("키움 모의투자 서버로 주문 취소를 요청하고 있습니다.");
            CompletableFuture.supplyAsync(() -> tradingUseCase.cancel(orderId))
                    .whenComplete((cancelled, failure) -> Platform.runLater(() -> {
                if (failure == null) {
                String message = name + " 주문번호 " + orderId + " 을(를) 취소했습니다. 상태 "
                        + cancelled.status().displayName();
                status.setText(message);
                addNotification("주문", message);
                announce(message, SpeechPriority.ORDER, "order-cancel-" + orderId);
                play(SoundCue.SUCCESS);
                screenController.invalidate(Screen.TRADING);
                screenController.invalidate(Screen.ACCOUNT);
                } else {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure;
                String reason = cause.getMessage() == null || cause.getMessage().isBlank()
                        ? cause.getClass().getSimpleName() : cause.getMessage();
                // 취소 실패를 성공처럼 보이게 두면 안 된다. 주문은 아직 살아 있을 수 있다.
                status.setText("주문 취소에 실패했습니다. " + reason);
                addNotification("주문", "주문번호 " + orderId + " 취소에 실패했습니다. " + reason);
                announce("주문 취소에 실패했습니다. " + reason, SpeechPriority.CRITICAL,
                        "order-cancel-failed-" + orderId);
                play(SoundCue.ERROR);
                showInformation("주문을 취소하지 못했습니다", reason
                        + "\n\n주문이 아직 남아 있을 수 있습니다. 미체결 목록을 다시 확인해주세요.");
                }
            }));
        });
    }

    /**
     * 미체결 주문을 모두 취소한다.
     *
     * <p>한 건이라도 실패하면 몇 건이 남았는지 함께 알린다. 일부만 취소되었는데 전부
     * 취소되었다고 안내하면, 남은 주문이 그대로 체결될 수 있다.
     */
    private void cancelAllOrders(TableView<ObservableList<String>> table) {
        List<String> orderIds = table.getItems().stream().map(row -> row.get(0)).toList();
        if (orderIds.isEmpty()) {
            showInformation("취소할 주문이 없습니다", "미체결 주문이 없습니다.");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "미체결 주문 " + orderIds.size() + "건을 모두 취소하시겠습니까?",
                ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("미체결 전량 취소 재확인");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            status.setText("키움 모의투자 서버로 " + orderIds.size() + "건의 취소를 요청하고 있습니다.");
            CompletableFuture.supplyAsync(() -> {
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
                return failures.isEmpty()
                        ? "미체결 주문 " + cancelled + "건을 취소했습니다."
                        : "미체결 주문 " + cancelled + "건을 취소했고 " + failures.size()
                                + "건은 취소하지 못했습니다. 주문번호 " + String.join(", ", failures);
            }).whenComplete((message, failure) -> Platform.runLater(() -> {
                String resultMessage = failure == null ? message : "미체결 주문 취소 중 오류가 발생했습니다.";
                boolean partialFailure = failure != null || resultMessage.contains("취소하지 못했습니다");
                status.setText(resultMessage);
                addNotification("주문", resultMessage);
                announce(resultMessage, partialFailure ? SpeechPriority.CRITICAL : SpeechPriority.ORDER,
                        "order-cancel-all");
                play(partialFailure ? SoundCue.ERROR : SoundCue.SUCCESS);
                screenController.invalidate(Screen.TRADING);
                screenController.invalidate(Screen.ACCOUNT);
            }));
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

    /** 관심종목 목록을 실제 실시간 이상 감시 구독과 동기화한다. */
    private void refreshAnomalyMonitoring() {
        long generation = ++anomalyMonitoringGeneration;
        trackNewsForWatchedStocks();
        anomalySubscriptions.values().forEach(EventSubscription::close);
        anomalySubscriptions.clear();
        synchronized (anomalyDetector) {
            anomalyDetector.reset();
        }
        for (WatchlistItem item : session.watchlistItems()) {
            if (item.needsIdentityRepair()) continue;
            String key = item.exchange() + ':' + item.symbol();
            CompletableFuture.runAsync(() -> {
                if (generation == anomalyMonitoringGeneration) {
                    addAnomalySubscription(key, item.securityId(), item.securityName());
                }
            });
        }
        // 보유종목은 관심종목에 넣지 않아도 감시한다. 계좌 조회는 화면 스레드를 막지 않는다.
        CompletableFuture.supplyAsync(tradingUseCase::account).whenComplete((account, failure) ->
                Platform.runLater(() -> {
                    if (failure != null || generation != anomalyMonitoringGeneration) return;
                    for (Position position : account.positions()) {
                        CompletableFuture.runAsync(() -> {
                            if (generation == anomalyMonitoringGeneration) {
                                addAnomalySubscription("KRX:" + position.symbol(),
                                        SecurityId.of(position.symbol(), "KRX"), position.name());
                            }
                        });
                    }
                    if (screenController != null) screenController.invalidate(Screen.ANOMALY);
                    refreshSubscriptionCount();
                }));
        if (screenController != null) screenController.invalidate(Screen.ANOMALY);
        refreshSubscriptionCount();
    }

    /**
     * 보유·관심 종목의 뉴스를 미리 받아 두게 한다.
     *
     * <p>구글 뉴스 RSS 는 최근 7일까지만 준다. 오늘 안 받으면 그날치는 영영 없고, 아카이브가
     * 비면 뉴스 피처가 중립으로 채워지면서 오류 하나 없이 예측만 서서히 무뎌진다.
     *
     * <p>감시 목록을 새로 잡을 때 함께 알린다. 사용자가 실제로 보는 종목이 곧 쌓아야 할
     * 종목이라 목록을 따로 관리할 이유가 없다.
     */
    private void trackNewsForWatchedStocks() {
        List<SecurityId> watched = new java.util.ArrayList<>();
        for (WatchlistItem item : session.watchlistItems()) {
            if (!item.needsIdentityRepair()) {
                watched.add(item.securityId());
            }
        }
        newsViewModel.track(watched);
        CompletableFuture.supplyAsync(tradingUseCase::account).thenAccept(account -> {
            List<SecurityId> held = new java.util.ArrayList<>();
            for (Position position : account.positions()) {
                // 계좌는 국내 모의투자라 보유 종목은 KRX 다. 관심 목록은 미국이 섞일 수
                // 있어 저장해 둔 거래소를 그대로 쓴다.
                held.add(SecurityId.of(position.symbol(), "KRX"));
            }
            newsViewModel.track(held);
        }).exceptionally(failure -> null);
    }

    private void addAnomalySubscription(String key, SecurityId security, String name) {
        if (anomalySubscriptions.containsKey(key)) return;
        try {
            EventSubscription subscription = marketApplication.monitor(security, new MarketApplicationListener() {
                @Override public void onQuote(Quote quote) {
                    List<AnomalyAlert> detected;
                    synchronized (anomalyDetector) {
                        detected = anomalyDetector.onQuote(name, quote);
                    }
                    if (!detected.isEmpty()) {
                        Platform.runLater(() -> detected.forEach(DesktopApplication.this::publishAnomaly));
                    }
                }

                @Override public void onConnectionChanged(ConnectionState state, String safeDetail) {
                    // 전역 연결 상태가 같은 정보를 표시한다. 여기서는 시세만 탐지기에 전달한다.
                }
            });
            anomalySubscriptions.put(key, subscription);
        } catch (RuntimeException failure) {
            Platform.runLater(() -> status.setText(
                    name + " 이상 감시를 시작하지 못했습니다: " + failure.getMessage()));
        }
    }

    private void publishAnomaly(AnomalyAlert alert) {
        String severity = alert.severity() == AnomalySeverity.HIGH ? "높음" : "주의";
        addNotification("이상 감지", severity + " · " + alert.explanation());
        status.setText("이상 감지: " + alert.explanation());
        announce(alert.explanation(), SpeechPriority.ALERT,
                "anomaly-" + alert.symbol() + '-' + alert.type());
        play(alert.severity() == AnomalySeverity.HIGH ? SoundCue.ANOMALY_HIGH : SoundCue.WARNING);
        if (screenController != null) screenController.invalidate(Screen.ANOMALY);
    }

    /** 손익 금액을 부호와 함께 표기한다. */

    private void previewOrder(TextField symbol, TextField name, ComboBox<OrderSide> side,
                              ComboBox<OrderType> orderType, Spinner<Integer> quantity, TextField price) {
        try {
            BigDecimal referencePrice = stockDetailViewModel.detail().currentPrice();
            OrderCommand request = orderType.getValue() == OrderType.MARKET
                    ? OrderCommand.market(symbol.getText().trim(), name.getText().trim(), side.getValue(),
                            quantity.getValue())
                    : OrderCommand.limit(symbol.getText().trim(), name.getText().trim(), side.getValue(),
                            quantity.getValue(), new BigDecimal(price.getText().replace(",", "").trim()));
            status.setText("키움 모의계좌의 주문 가능 금액을 확인하고 있습니다.");
            CompletableFuture.supplyAsync(() -> tradingUseCase.preview(request, referencePrice))
                    .whenComplete((preview, failure) -> Platform.runLater(() -> {
                        if (failure != null) showOrderFailure("주문 미리보기를 만들지 못했습니다", failure);
                        else showOrderConfirmation(request, referencePrice, preview);
                    }));
        } catch (RuntimeException exception) {
            showOrderFailure("주문 입력을 확인하세요", exception);
        }
    }

    private void showOrderConfirmation(OrderCommand request, BigDecimal referencePrice, TradePreview result) {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("키움 모의주문 재확인");
        confirmation.setHeaderText(request.name() + " " + request.quantity() + "주 "
                + request.side().displayName() + " 주문을 제출하시겠습니까?");
        String orderPrice = request.type() == OrderType.MARKET ? "시장가" : Formatters.won(request.limitPrice());
        confirmation.setContentText("종목 코드: " + request.symbol() + "\n주문 가격: " + orderPrice
                + "\n예상 주문금액: " + Formatters.won(result.estimatedAmount()) + costLines(result)
                + "\n주문 후 예상 현금: " + Formatters.won(result.availableCashAfter())
                + "\n\n확인하면 키움 모의투자 서버로 주문을 전송합니다. 실전 주문은 아닙니다.");
        confirmation.getDialogPane().setAccessibleText(result.describe());
        ButtonType submit = new ButtonType("모의 " + request.side().displayName() + " 제출", ButtonBar.ButtonData.OK_DONE);
        confirmation.getButtonTypes().setAll(submit, ButtonType.CANCEL);
        confirmation.showAndWait().filter(submit::equals).ifPresent(button -> {
            if (preventDuplicateOrders && isRapidDuplicateOrder(request)) {
                showInformation("중복 주문을 차단했습니다",
                        "같은 종목·구분·가격·수량의 주문이 방금 제출됐습니다.\n다시 제출하려면 3초 후 시도해주세요.");
                return;
            }
            status.setText("키움 모의투자 서버로 주문을 전송하고 있습니다.");
            CompletableFuture.supplyAsync(() -> tradingUseCase.submitConfirmed(request, referencePrice))
                    .whenComplete((receipt, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            showOrderFailure("키움 모의주문을 접수하지 못했습니다", failure);
                            return;
                        }
                        rememberSubmittedOrder(request);
                        String receiptMessage = receipt.describe();
                        status.setText(receiptMessage + " 주문번호 " + receipt.orderId());
                        addNotification("주문", receiptMessage + " 주문번호 " + receipt.orderId());
                        announce(receiptMessage, SpeechPriority.ORDER, "order-" + receipt.orderId());
                        play(SoundCue.SUCCESS);
                        Alert completed = new Alert(Alert.AlertType.INFORMATION);
                        completed.setTitle("키움 모의주문 접수 결과");
                        completed.setHeaderText(receiptMessage);
                        completed.setContentText("주문번호: " + receipt.orderId()
                                + "\n주문 화면에 머물거나 이전 화면으로 돌아갈 수 있습니다.");
                        ButtonType back = new ButtonType("이전 화면으로 돌아가기", ButtonBar.ButtonData.BACK_PREVIOUS);
                        ButtonType stay = new ButtonType("주문 화면 유지", ButtonBar.ButtonData.OK_DONE);
                        completed.getButtonTypes().setAll(back, stay);
                        completed.showAndWait().filter(back::equals).ifPresent(resultButton -> navigateBack());
                    }));
        });
    }

    private void showOrderFailure(String header, Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        String reason = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName() : cause.getMessage();
        status.setText(header + ": " + reason);
        addNotification("주문", header + ". " + reason);
        announce(header + ". " + reason, SpeechPriority.CRITICAL, "order-error");
        play(SoundCue.ERROR);
        Alert alert = new Alert(Alert.AlertType.ERROR, reason, ButtonType.OK);
        alert.setHeaderText(header);
        alert.showAndWait();
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
        realtimeStatus.getStyleClass().add("status-item");
        subscriptionCount.getStyleClass().add("status-item");
        applyConnectionState(ConnectionState.DISCONNECTED, null);
        Label mode = new Label("모의투자"); mode.getStyleClass().addAll("status-item", "status-mock");
        lastDataTime.setText(live ? "조회 시세 · 요청 시점 기준" : "데모 시세 · 로컬 스냅샷");
        lastDataTime.getStyleClass().add("status-item");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(14, status, spacer, rest, realtimeStatus, mode, subscriptionCount, lastDataTime);
        bar.setAlignment(Pos.CENTER_LEFT); bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(9, 16, 9, 16)); return bar;
    }

    private void restoreLocalState() {
        stateRepository.load().ifPresent(snapshot -> {
            session.restore(snapshot);
            stockSearchViewModel.recentSearches().setAll(snapshot.recentSearches());
            preventDuplicateOrders = snapshot.preventDuplicateOrders();
        });
        // 기동할 때도 같은 길을 쓴다. 여기서만 따로 적용하면 첫 실행과 이후가 갈라진다.
        accessibility = accessibilityPreferencesRepository.load();
        if (!speechQueue.isClosed()) {
            speechQueue.setOptions(accessibility.speechOptions());
        }
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
                    List.copyOf(stockSearchViewModel.recentSearches()), List.copyOf(session.notifications()),
                    List.copyOf(session.journalEntries()), session.selectedStock(),
                    preventDuplicateOrders));
            // 음성 설정만 합성기에서 읽어 채운다. 나머지는 이미 들고 있는 값 그대로다.
            accessibilityPreferencesRepository.save(accessibility.withVoice(
                    speech.voiceName() == null ? "" : speech.voiceName(),
                    speech.rate(), speech.volume()));
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
        if (!accessibility.speechEnabled() || speechQueue.isClosed()) {
            status.setText("음성 안내가 꺼져 있습니다. 설정에서 화면 읽기(TTS)를 켜주세요.");
            play(SoundCue.WARNING);
            return;
        }
        announce(text, SpeechPriority.USER_REQUEST, key);
    }

    private void announce(String text, SpeechPriority priority, String key, SpeechMergePolicy mergePolicy) {
        if (accessibility.speechEnabled() && !speechQueue.isClosed()) {
            speechQueue.announce(new SpeechRequest(text, priority, key, mergePolicy));
        }
    }
    private void play(SoundCue cue) { if (accessibility.soundEnabled()) soundPort.play(cue); }
    /**
     * 바뀐 접근성 설정을 한 번에 적용하고 저장한다.
     *
     * <p>화면이 합성기를 직접 만지고 저장은 다른 곳에서 하면 한쪽만 도는 경우가 생긴다 —
     * 소리는 바뀌었는데 다음 실행 때 되돌아가거나 그 반대다. 적용과 저장을 여기 한 곳에
     * 묶어 둔다.
     *
     * <p>일곱 가지를 모두 다시 적용한다. 바뀐 것만 골라 적용하면 무엇이 바뀌었는지 화면이
     * 알려 줘야 하고, 그 판단이 화면마다 갈라진다. 전부 다시 거는 편이 싸고 어긋나지 않는다.
     */
    private void applyAccessibility(AccessibilityPreferences updated) {
        accessibility = updated;
        if (!speechQueue.isClosed()) {
            speechQueue.setOptions(updated.speechOptions());
        }
        if (!updated.speechEnabled()) {
            speechQueue.clear();
        }
        applyKeyboardGuidance(updated.keyboardGuidanceEnabled());
        toggleClass("reduced-motion", updated.reducedMotionEnabled());
        toggleClass("large-text", updated.largeTextEnabled());
        toggleClass("high-contrast", updated.highContrastEnabled());
        applyInformationDensity(updated.informationDensity());
        scheduleStateSave();
    }

    private void applyKeyboardGuidance(boolean enabled) {
        toggleClass("keyboard-guidance-off", !enabled);
        if (root != null) {
            for (Node guide : root.lookupAll(".keyboard-help")) {
                guide.setVisible(enabled);
                guide.setManaged(enabled);
            }
        }
        if (globalSearchKeyboardHelp != null) {
            globalSearchKeyboardHelp.setVisible(enabled);
            globalSearchKeyboardHelp.setManaged(enabled);
        }
    }

    private boolean isRapidDuplicateOrder(OrderCommand request) {
        String fingerprint = orderFingerprint(request);
        long elapsed = System.nanoTime() - lastSubmittedOrderNanos;
        return fingerprint.equals(lastSubmittedOrderFingerprint)
                && elapsed >= 0 && elapsed < java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    }

    private void rememberSubmittedOrder(OrderCommand request) {
        lastSubmittedOrderFingerprint = orderFingerprint(request);
        lastSubmittedOrderNanos = System.nanoTime();
    }

    private static String orderFingerprint(OrderCommand request) {
        return request.symbol() + '|' + request.side() + '|' + request.type() + '|'
                + request.quantity() + '|' + (request.limitPrice() == null ? "MARKET" : request.limitPrice().stripTrailingZeros());
    }

    private void applyInformationDensity(String density) {
        if (root == null) return;
        root.getStyleClass().removeAll("density-compact", "density-detailed");
        if ("좁게".equals(density)) root.getStyleClass().add("density-compact");
        if ("넓게".equals(density)) root.getStyleClass().add("density-detailed");
    }

    private void toggleClass(String name, boolean enabled) {
        if (enabled && !root.getStyleClass().contains(name)) root.getStyleClass().add(name);
        if (!enabled) root.getStyleClass().remove(name);
    }

    @Override public void stop() {
        if (persistenceDelay != null) persistenceDelay.stop();
        saveLocalState();
        anomalySubscriptions.values().forEach(EventSubscription::close);
        anomalySubscriptions.clear();
        stockDetailViewModel.stopLiveChart();
        orderBookViewModel.stop();
        tradeTapeViewModel.stop();
        if (connectionWatch != null) connectionWatch.close();
        // 앱이 꺼지는데 자식이 남으면 포트를 붙잡고 있어 다음 실행이 실패한다.
        if (aiServiceProcess != null) aiServiceProcess.close();
        if (subscriptionTicker != null) subscriptionTicker.stop();
        if (accessibleChartController != null) accessibleChartController.close();
        marketApplication.close();
        sonificationPort.close();
        speechQueue.close();
        soundPort.close();
        secretStore.close();
    }
    public static void main(String[] args) { launch(args); }
}
