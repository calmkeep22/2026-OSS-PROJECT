package org.ossproject.desktop;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import org.ossproject.anomaly.AnomalyAlertRepository;
import org.ossproject.anomaly.AnomalySeverity;
import org.ossproject.anomaly.StreamingAnomalyConfig;
import org.ossproject.anomaly.StreamingAnomalyDetector;
import org.ossproject.desktop.composition.DesktopServices;
import org.ossproject.finance.model.*;
import org.ossproject.finance.model.account.*;
import org.ossproject.finance.model.market.*;
import org.ossproject.finance.model.order.*;
import org.ossproject.finance.model.orderbook.*;
import org.ossproject.desktop.ai.AiInsightListCoordinator;

import org.ossproject.desktop.viewmodel.AiInsightViewModel;
import org.ossproject.desktop.view.StockPicker;
import org.ossproject.desktop.view.WatchlistToggle;
import org.ossproject.desktop.view.screen.NewsScreenView;
import org.ossproject.desktop.view.screen.OrderFormView;
import org.ossproject.desktop.view.screen.StockChartPanel;
import org.ossproject.desktop.view.screen.StockScreenView;
import org.ossproject.desktop.view.screen.SettingsScreenView;
import org.ossproject.desktop.view.screen.SimilarScreenView;
import org.ossproject.desktop.view.screen.StockComparisonDialog;
import org.ossproject.ai.SimilarStock;
import org.ossproject.desktop.viewmodel.NewsViewModel;
import org.ossproject.desktop.viewmodel.OrderDraftViewModel;
import org.ossproject.desktop.ai.AiServiceProcess;
import org.ossproject.desktop.chart.AccessibleChartController;
import org.ossproject.desktop.chart.AccessibleChartView;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.desktop.presentation.Formatters;

import static org.ossproject.desktop.presentation.Formatters.assetsSource;
import static org.ossproject.desktop.presentation.Formatters.orderTime;
import static org.ossproject.desktop.presentation.Formatters.signedChangeRate;
import static org.ossproject.desktop.presentation.Formatters.signedWon;
import org.ossproject.desktop.navigation.NavigationIcons;
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
import org.ossproject.desktop.view.screen.SearchScreenView;
import org.ossproject.desktop.view.screen.ConnectionScreenView;
import org.ossproject.desktop.view.screen.AccountScreenView;
import org.ossproject.desktop.view.screen.AnomalyScreenView;
import org.ossproject.desktop.view.screen.ChatScreenView;
import org.ossproject.desktop.view.screen.DashboardScreenView;
import org.ossproject.desktop.view.screen.NotificationsScreenView;
import org.ossproject.desktop.view.screen.WatchlistScreenView;
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
    private static final System.Logger LOGGER = System.getLogger(DesktopApplication.class.getName());
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
    private final AnomalyAlertRepository anomalyAlertRepository;
    private final AutoCloseable persistence;
    private final AiInsightViewModel aiInsightViewModel;
    /** AI 서버를 앱이 띄웠으면 그 프로세스. 사용자가 직접 띄웠거나 못 띄웠으면 null. */
    private final NewsViewModel newsViewModel;
    private final org.ossproject.ai.MarketOverviewPort marketOverview;
    /** 지금 보고 있는 화면. 결과가 늦게 와도 그때 살아 있는 화면에만 넣는다. */
    private SimilarScreenView similarView;
    private NewsScreenView newsView;
    /** 이상 감지 화면의 다종목 AI 분석 로딩과 안정적인 표시 순서를 조정한다. */
    private final AiInsightListCoordinator aiInsightListCoordinator;
    /** 종목을 바꿔 다시 만든 화면. 고르개에 초점을 돌려 준다. */
    private Screen pickerFocusScreen;
    /**
     * 마지막으로 받은 분석.
     *
     * <p>챗봇이 근거로 쓴다. 서버가 다시 계산하면 그새 값이 바뀌어 사용자가 화면에서
     * 보고 있는 것과 다른 답을 듣는다.
     */
    private org.ossproject.ai.AiInsight lastInsight;
    /** {@link #lastInsight}가 어느 종목의 분석인지. 다른 종목 챗봇에 섞지 않는다. */
    private SecurityId lastInsightSecurity;
    private final AiServiceProcess aiServiceProcess;
    private final org.ossproject.voice.VoiceInputPort voiceInput;
    private final org.ossproject.voice.AudioCapturePort microphone;
    private org.ossproject.desktop.voice.VoiceCommandController voiceController;
    private final Button voiceButton = new Button("음성 명령");
    private final Button chatButton = new Button("AI 챗봇");
    /** 앱 시작과 함께 한 번 받아 모든 홈 재구성에서 공유하는 시장 지표. */
    /** 방금 알린 화면. 같은 화면을 다시 열 때 같은 말을 되풀이하지 않으려고 기억한다. */
    /** 주문 표 한 줄과 머리글 높이. CSS 의 .order-status-tabs .table-row-cell 과 맞춘다. */
    private static final double ORDER_ROW_HEIGHT = 32;
    private static final double ORDER_HEADER_HEIGHT = 34;
    private static final double LARGE_ORDER_ROW_HEIGHT = 56;
    private static final double LARGE_ORDER_HEADER_HEIGHT = 52;
    private Screen lastAnnouncedScreen;
    /** 질문 화면. 단축키가 질문 목록으로 곧장 초점을 옮길 수 있게 들고 있는다. */
    private ChatScreenView chatScreenView;
    private CompletableFuture<List<org.ossproject.ai.MarketIndex>> startupMarketOverview;
    /** 뒤에서 지표를 다시 받는 중인지. 홈을 그릴 때마다 새 요청이 쌓이지 않게 막는다. */
    private boolean marketOverviewRetrying;
    /** AI 서버가 열리기를 기다리며 지표를 다시 받아 볼 횟수와 간격. */
    private static final int MARKET_OVERVIEW_ATTEMPTS = 8;
    private static final javafx.util.Duration MARKET_OVERVIEW_RETRY_DELAY =
            javafx.util.Duration.seconds(3);
    /** 마지막으로 읽어 준 말. "다시 말해줘" 가 이것을 되풀이한다. */
    private String lastSpoken = "";
    /** 말로 정한 주문 수량. 다음 주문 화면 하나에만 쓰이고 1 로 되돌아간다. */
    private int pendingOrderQuantity = 1;
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

    /**
     * 한글을 조합하는 중인가.
     *
     * <p>추천 팝업이 뜨면 포커스를 가져가고, 그 순간 조합 중이던 글자가 자모로 풀린다.
     * "네이버" 를 치면 "ㄴㅔ이버" 가 되었다. 첫 글자만 깨지는 이유는 팝업이 한 번만
     * 뜨기 때문이다. 조합이 끝날 때까지 팝업을 미룬다.
     */
    private boolean composingHangul;
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
        this.anomalyAlertRepository = services.anomalyAlerts();
        this.persistence = services.persistence();
        this.aiInsightViewModel = new AiInsightViewModel(
                services.market(), services.aiInsight(), Platform::runLater);
        this.newsViewModel = new NewsViewModel(services.news(), Platform::runLater);
        this.marketOverview = services.marketOverview();
        this.aiServiceProcess = services.aiServiceProcess();
        this.aiInsightListCoordinator = new AiInsightListCoordinator(
                aiInsightViewModel, aiServiceProcess, tradingUseCase::account,
                () -> List.copyOf(session.watchlistItems()),
                text -> requestSpeech(text, "ai-insight"), Platform::runLater);
        this.voiceInput = services.voice();
        this.microphone = services.microphone();
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
        preloadMarketOverview();
        session.onChange(this::scheduleStateSave);
        stockSearchViewModel.recentSearches().addListener(
                (javafx.collections.ListChangeListener<String>) change -> scheduleStateSave());
        session.watchlistItems().addListener(
                (javafx.collections.ListChangeListener<WatchlistItem>) change -> {
                    refreshAnomalyMonitoring();
                    refreshVoiceVocabulary();
                });
        root = new BorderPane();
        // 글꼴은 CSS 가 아니라 여기서 정한다. JavaFX 는 -fx-font-family 의 쉼표 목록을
        // 받지 않아, 대안을 늘어놓으면 통째로 무시하고 기본 글꼴로 떨어진다.
        root.setStyle(org.ossproject.desktop.view.AppFonts.rootStyle(
                org.ossproject.desktop.view.AppFonts.install()));
        // Figma 무채색 테마는 색·글꼴·테두리만 맡는다. 화면별 배치 클래스와 크기 계산은
        // 그대로 두어 기존 화면이 밀리거나 잘리지 않게 한다.
        root.getStyleClass().addAll("app-root", "figma-neutral-theme");
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
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.ALT_DOWN),
                () -> focusGlobalSearch());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.O, KeyCombination.ALT_DOWN),
                () -> openOrder(OrderSide.BUY));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.V, KeyCombination.ALT_DOWN),
                this::startVoiceCommand);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.X, KeyCombination.ALT_DOWN),
                this::stopSpeechNow);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN),
                this::navigateBack);
        installScreenNumberShortcuts(scene);
        // 질문 화면의 두 자리로 곧장 간다. 탭으로 훑어 찾게 두면, 화면을 볼 수 없는
        // 사용자는 어디쯤에서 멈춰야 하는지 매번 다시 세어야 한다.
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Q, KeyCombination.ALT_DOWN),
                this::focusChatQuestions);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.ALT_DOWN),
                this::focusStockPicker);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.ALT_DOWN),
                this::listenToLatestChatAnswer);
        // 단축키는 적혀 있지 않으면 없는 것과 같다. 화면을 볼 수 없는 사용자에게는
        // 더욱 그렇다 — 눌러 보다가 찾을 수가 없다. F1 로 언제든 전체를 펼친다.
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F1), this::showShortcutHelp);
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F6) {
                cycleFocusRegion(event.isShiftDown());
                event.consume();
            }
        });

        navigate(Screen.DASHBOARD);
        stage.setTitle("OpenStock Access - 모의투자 UI");
        // 창과 작업표시줄 아이콘. jpackage 의 --icon 은 설치본에만 붙으므로, 소스로
        // 실행할 때(개발·시연)는 여기서 넣지 않으면 기본 자바 아이콘이 나온다.
        // 여러 크기를 함께 주면 운영체제가 자리에 맞는 것을 고른다.
        for (int size : new int[]{16, 32, 48, 128, 256}) {
            java.io.InputStream stream =
                    getClass().getResourceAsStream("/branding/icon-" + size + ".png");
            if (stream != null) {
                stage.getIcons().add(new javafx.scene.image.Image(stream));
            }
        }
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
        navigationButtons.clear();
        // 참고 시안처럼 아이콘과 이름을 함께 표시한다. 기존 화면은 하나도 빼지 않는다.
        // 창 높이가 부족한 경우에만 이 영역 자체가 스크롤된다.
        VBox nav = new VBox(4);
        nav.setAlignment(Pos.TOP_LEFT);
        Screen.NavigationGroup previousGroup = null;
        for (Screen screen : Screen.values()) {
            if (!screen.shownInSidebar()) continue;
            if (previousGroup != null && previousGroup != screen.navigationGroup()) {
                Separator separator = new Separator();
                separator.getStyleClass().add("nav-rail-separator");
                nav.getChildren().add(separator);
            }
            Button button = new Button(screen.label());
            button.setGraphic(navigationIcon(screen));
            button.getStyleClass().addAll("nav-button", "nav-rail-button");
            button.setContentDisplay(ContentDisplay.LEFT);
            button.setGraphicTextGap(12);
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
        navScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        useBrowserLikeScrolling(navScroll);
        VBox.setVgrow(navScroll, Priority.ALWAYS);
        VBox sidebar = new VBox(navScroll);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_LEFT);
        sidebar.setPadding(new Insets(8, 6, 8, 6));
        sidebar.setPrefWidth(164);
        sidebar.setMinWidth(156);
        sidebar.setMaxWidth(168);
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
        hotspot.visibleProperty().bind(autoHideSidebar.visibleProperty().not());

        sidebarHideDelay.setOnFinished(event -> hideSidebar());

        // 사이드바를 본문 위에 겹쳐 놓으면 왼쪽 72픽셀이 가려진다. 홈처럼 가운데로 모으는
        // 화면에서는 티가 나지 않지만, 종목 상세처럼 왼쪽부터 채우는 화면에서는 종목명과
        // 현재가가 잘린다. 화면을 확대해 쓰는 사용자에게는 왼쪽 한 줄이 통째로 사라지는
        // 셈이라 더 나쁘다. 같은 줄에 나란히 두면 사이드바가 자리를 차지하고 본문이
        // 그만큼 밀린다 — 숨을 때는 managed 가 false 라 자리를 돌려준다.
        // 화면 열두 개 중 스크롤을 스스로 가진 것은 둘뿐이다. 나머지는 창보다 길면 그냥
        // 잘린다. 화면 배율 150% 에서는 1920x1080 짜리 화면도 논리 1280x650 이라, 세로가
        // 모자라 주문 상태 표와 취소 단추가 창 밖으로 나간다. 여기서 한 번 감싸면 어느
        // 화면이든 잘리는 대신 스크롤된다.
        //
        // 일반 화면은 뷰포트 높이를 채운다. 큰 글자 대응을 이유로 이 값을 끄면 홈의
        // GridPane이 남는 높이를 카드에 나눠 줘 일반 글자 카드까지 지나치게 커진다.
        // 긴 화면은 각 화면의 내부 ScrollPane과 컴포넌트 최소 높이로 처리한다.
        // 가로 스크롤은 끈다 — 좌우로 밀어야 읽히는 표는 화면을 볼 수 없는 사용자에게
        // 사실상 없는 것과 같다.
        ScrollPane workspaceScroll = new ScrollPane(screenHost);
        workspaceScroll.setFitToWidth(true);
        workspaceScroll.setFitToHeight(true);
        workspaceScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        workspaceScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        workspaceScroll.getStyleClass().add("workspace-scroll");
        workspaceScroll.setMinSize(0, 0);
        useBrowserLikeScrolling(workspaceScroll);

        // 펼친 메뉴는 본문 위에 떠 있는 서랍으로 보인다. 본문 폭을 줄이지 않으므로
        // 종목 헤더의 매도·매수 단추나 넓은 표가 오른쪽 밖으로 밀리지 않는다.
        StackPane workspace = new StackPane(workspaceScroll, autoHideSidebar, hotspot);
        StackPane.setAlignment(autoHideSidebar, Pos.TOP_LEFT);
        StackPane.setAlignment(hotspot, Pos.TOP_LEFT);
        workspace.getStyleClass().add("workspace");
        workspace.setMinSize(0, 0);
        workspace.setOnMouseMoved(event -> {
            if (event.getX() <= 24) showSidebar();
            else if (autoHideSidebar != null && autoHideSidebar.isVisible()
                    && event.getX() > autoHideSidebar.getWidth() + 8) {
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
        // toFront() 를 부르지 않는다. 겹쳐 쌓을 때는 맨 앞으로 올리는 뜻이었지만 이제는
        // 같은 줄에 나란히 있어서, 자식 순서를 바꾸면 사이드바가 본문 오른쪽으로 간다.
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
        String data = NavigationIcons.pathFor(screen);
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
        // 폭이 모자라면 JavaFX 는 글자를 "..." 으로 줄인다. 뒤로가기와 검색이 "..." 이
        // 되면 무슨 단추인지 알 수 없고, 스크린리더도 "..." 을 읽는다. 줄일 것은 검색
        // 입력칸이지 단추가 아니다.
        backButton.setMinWidth(Region.USE_PREF_SIZE);
        backButton.setAccessibleText("이전 화면으로 돌아가기");
        backButton.setAccessibleHelp("Alt와 왼쪽 방향키로도 이전 화면으로 돌아갈 수 있습니다.");
        backButton.setOnAction(event -> navigateBack());
        currentLocation.getStyleClass().add("muted-text");
        currentLocation.setAccessibleText("현재 화면 홈");

        globalSearch.setPromptText("종목명 또는 종목코드 검색");
        globalSearch.setAccessibleText("국내 종목 통합 검색");
        globalSearch.setAccessibleHelp("검색어를 입력하고 Enter 키를 누르면 종목 상세 화면을 엽니다.");
        globalSearch.setPrefWidth(360);
        globalSearch.setMinWidth(180);
        // 거르개가 Enter 를 이미 consume 하므로 평소에는 여기까지 오지 않는다.
        // 검사처럼 ActionEvent 를 직접 쏘는 길을 위해 남겨 둔다.
        globalSearch.setOnAction(event -> openSearchedStock());
        configureGlobalSearchMenu();

        Button searchButton = new Button("검색");
        searchButton.setMinWidth(Region.USE_PREF_SIZE);
        searchButton.setOnAction(event -> openSearchedStock());
        HBox search = new HBox(8, globalSearch, searchButton);
        search.getStyleClass().add("global-search-shell");
        search.setAlignment(Pos.CENTER_LEFT);
        search.setMinWidth(240);
        search.setPrefWidth(420);
        // 오른쪽 상태 단추를 밀어 두 번째 줄로 보내는 빈 공간 대신 검색창이 남는 폭을
        // 사용한다. 창이 좁아지면 먼저 이 입력칸이 최소 폭까지 줄어든다.
        search.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(globalSearch, Priority.ALWAYS);

        // 상단 표시는 실제 상태를 따른다. 연결되어 있는데 미연결로 보이거나 그 반대면,
        // 화면을 볼 수 없는 사용자는 지금 값이 실제 시세인지 판단할 근거를 잃는다.
        Label market = new Label("시세 공급원 · " + marketDataSource);
        market.getStyleClass().addAll("status-chip", "mode-badge");
        market.setAccessibleText("시세 출처. " + marketDataSource);
        connectionButton.getStyleClass().add("connection-button");
        connectionButton.setOnAction(event -> navigate(Screen.CONNECTION));
        // 상단에서는 뺐지만 객체는 남긴다. 연결 상태 문구를 여기에 계속 써 넣고 있고,
        // 하단 상태 줄과 API 연결 화면이 그 값을 읽는다.
        connectionButton.setVisible(false);
        connectionButton.setManaged(false);

        configureVoiceButton();
        configureChatButton();

        HBox.setHgrow(search, Priority.ALWAYS);
        currentLocation.setMinWidth(0);
        currentLocation.setMaxWidth(180);
        currentLocation.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        // 연결 상태는 상단 바의 일부이므로 혼자 다음 줄로 내려가지 않게 한 줄 묶음으로
        // 둔다. 부족한 폭은 왼쪽 검색창이 먼저 줄어든다.
        // 알림·계좌·실시간 연결은 상단에서 뺐다. 셋 다 사이드바에 같은 항목이 있고,
        // 연결 상태는 하단 상태 줄이 계속 보여 준다 — 같은 것을 두 곳에 두면 상단이
        // 넘쳐 단추끼리 겹치기까지 했다.
        //
        // 남긴 셋은 사이드바에 없는 것이다. 시세 출처는 지금 보고 있는 값이 어디서
        // 왔는지이고, 챗봇과 음성은 어느 화면에서든 바로 불러야 하는 것이다.
        HBox actions = new HBox(6, market, chatButton, voiceButton);
        actions.getStyleClass().add("top-actions");
        actions.setAlignment(Pos.CENTER_LEFT);
        // 묶음 전체를 pref 로 못 박지 않는다. 그러면 글자가 조금만 넓어져도 상단 바가
        // 창을 넘어 단추들이 서로 겹쳐 그려진다 — 글꼴을 Pretendard 로 바꾼 뒤 실제로
        // 그렇게 됐다. 대신 줄어드는 차례를 정해 준다.
        actions.setMinWidth(0);
        // 가장 먼저 줄어드는 것은 읽기만 하는 표시다. 잘려도 눌러야 할 것이 사라지지 않는다.
        market.setMinWidth(0);
        market.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        // 누르는 단추는 제 폭을 지킨다. 눌리면 "..." 만 남아 무엇을 누르는지 사라진다.
        for (javafx.scene.layout.Region pinned : List.of(chatButton, voiceButton)) {
            pinned.setMinWidth(Region.USE_PREF_SIZE);
        }
        // 검색칸의 기본 최소 폭은 안내 문구에서 나온다. 그대로 두면 상단 바가 좁아질 때
        // 이것이 버티느라 오른쪽 단추들을 밖으로 밀어낸다.
        search.setMinWidth(0);

        HBox context = new HBox(6, backButton, currentLocation, search, actions);
        context.setMinWidth(0);
        context.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(context);
        top.getStyleClass().add("top-bar");
        top.setPadding(new Insets(9, 14, 9, 14));
        return top;
    }

    /**
     * 말로 시킨 일을 실제 동작에 잇는다.
     *
     * <p>주문만 다르게 다룬다. 나머지는 바로 실행하고, 주문은 화면을 채워 두기만 한다.
     * 음성용 확인 절차를 새로 만들지 않고 이미 있는 재확인 창을 그대로 거치게 한다 —
     * 되돌릴 수 없는 일에 검증되지 않은 두 번째 길을 내지 않는다.
     */
    private org.ossproject.desktop.voice.VoiceActions createVoiceActions() {
        return new org.ossproject.desktop.voice.VoiceActions() {

            @Override public void navigate(Screen screen) {
                DesktopApplication.this.navigate(screen);
            }

            @Override public void goBack() {
                navigateBack();
            }

            @Override public void quote(org.ossproject.voice.KnownStock stock) {
                openStockByQuery(stock.symbol());
            }

            @Override public void openNews(org.ossproject.voice.KnownStock stock) {
                selectThen(stock, Screen.NEWS);
            }

            @Override public void openSimilar(org.ossproject.voice.KnownStock stock) {
                selectThen(stock, Screen.SIMILAR);
            }

            @Override public void addToWatchlist(org.ossproject.voice.KnownStock stock) {
                addToWatchlistBySymbol(stock.symbol(), stock.name());
            }

            @Override public void removeFromWatchlist(org.ossproject.voice.KnownStock stock) {
                // 거래소는 담을 때 조회로 채워 둔 값을 쓴다. 여기서 추측하면 KRX 와
                // NXT 종목을 잘못 골라 엉뚱한 것을 뺀다.
                session.watchlistItems().stream()
                        .filter(item -> item.symbol().equals(stock.symbol()))
                        .findFirst()
                        .ifPresentOrElse(
                                item -> DesktopApplication.this.removeFromWatchlist(
                                        item.symbol(), item.exchange(), item.securityName()),
                                () -> tell(stock.name() + "은 관심종목에 없습니다."));
            }

            @Override public void readBalance() {
                CompletableFuture.supplyAsync(tradingUseCase::account)
                        .whenComplete((snapshot, failure) -> Platform.runLater(() -> {
                            if (failure != null || snapshot == null) {
                                tell("잔고를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                                return;
                            }
                            tell("총 자산 " + Formatters.won(snapshot.totalAssets())
                                    + ", 평가손익은 " + signedWon(snapshot.totalProfitLoss())
                                    + ", 주문 가능 금액은 "
                                    + Formatters.won(snapshot.deposits().orderable()) + " 입니다.");
                        }));
            }

            @Override public void prepareOrder(org.ossproject.voice.KnownStock stock,
                                               boolean buy, int quantity) {
                selectStockByQuery(stock.symbol()).thenAccept(found -> {
                    if (found) openOrder(buy ? OrderSide.BUY : OrderSide.SELL, quantity);
                    else tell(stock.name() + " 종목 정보를 찾지 못해 주문 화면을 열지 못했습니다.");
                });
            }

            @Override public void openPendingOrders() {
                DesktopApplication.this.navigate(Screen.TRADING);
            }

            @Override public void findStock(String hint,
                                            java.util.function.Consumer<
                                                    org.ossproject.voice.KnownStock> andThen) {
                // 등록명이 영문이어도 찾는다. 그 규칙은 도메인의 SecuritySummary 가
                // 들고 있어서, 검색창으로 치든 말로 하든 같은 방식으로 맞는다.
                stockSearchViewModel.findBestMatch(hint).thenAccept(item -> {
                    if (item == null) {
                        tell(hint + " 종목을 찾지 못했습니다. 종목명을 다시 말씀해주세요.");
                        return;
                    }
                    // 찾은 종목을 골라 둔다. 이어지는 일이 이 종목을 대상으로 돈다.
                    stockSearchViewModel.select(item);
                    andThen.accept(new org.ossproject.voice.KnownStock(
                            item.symbol(), item.name()));
                }).exceptionally(failure -> {
                    Platform.runLater(() -> tell(hint + " 종목을 조회하지 못했습니다."));
                    return null;
                });
            }

            @Override public void stopSpeech() {
                DesktopApplication.this.stopSpeechNow();
            }

            @Override public void startedListening() {
                // 읽던 것을 먼저 멈춘다. 안 멈추면 그 소리가 마이크로 다시 들어간다.
                speechQueue.clear();
                speechPort.stop();
                voiceButton.setText("듣는 중");
                status.setText("듣고 있습니다. 말씀해주세요.");
                play(SoundCue.LISTENING);
            }

            @Override public void repeatLast() {
                if (lastSpoken.isBlank()) {
                    tell("다시 읽어 드릴 안내가 없습니다.");
                    return;
                }
                requestSpeech(lastSpoken, "voice-repeat");
            }

            @Override public void adjustSpeechRate(boolean faster) {
                double rate = Math.max(0.5, Math.min(2.0,
                        accessibility.speechRate() + (faster ? 0.2 : -0.2)));
                applyAccessibility(accessibility.withSpeechRate(rate));
                tell("읽기 속도를 " + String.format("%.1f", rate) + "배로 맞췄습니다.");
            }

            @Override public void toggleLargeText() {
                boolean on = !accessibility.largeTextEnabled();
                applyAccessibility(accessibility.withLargeTextEnabled(on));
                tell(on ? "큰 글씨를 켰습니다." : "큰 글씨를 껐습니다.");
            }

            @Override public void toggleHighContrast() {
                boolean on = !accessibility.highContrastEnabled();
                applyAccessibility(accessibility.withHighContrastEnabled(on));
                tell(on ? "고대비를 켰습니다." : "고대비를 껐습니다.");
            }

            @Override public void help() {
                tell("이렇게 말씀하실 수 있습니다. 관심종목 보여줘. 계좌. 청각 차트."
                        + " 뒤로. 삼성전자 현재가. 카카오 뉴스. 삼성전자 관심종목에 담아줘."
                        + " 예수금 알려줘. 그만. 다시 말해줘."
                        + " 주문은 삼성전자 매수 열 주 처럼 말하면 주문 화면을 채워 드립니다."
                        + " 실제 주문은 확인 단추를 눌러야 나갑니다.");
            }

            @Override public void tell(String message) {
                voiceButton.setText("음성 명령");
                status.setText(message);
                requestSpeech(message, "voice-reply");
            }
        };
    }

    /** 종목을 고른 뒤 화면을 연다. 조회가 실패하면 화면을 옮기지 않는다. */
    private void selectThen(org.ossproject.voice.KnownStock stock, Screen screen) {
        selectStockByQuery(stock.symbol()).thenAccept(found -> {
            if (found) navigate(screen);
            else status.setText(stock.name() + " 종목 정보를 찾지 못했습니다.");
        });
    }

    /**
     * 마이크 단추를 준비한다.
     *
     * <p>단추를 눌러야 듣는다. 늘 듣고 있지 않는다 — 마이크를 계속 열어 두면 사용자가
     * 언제 녹음되는지 알 수 없고, 그 사실을 화면으로 확인할 수도 없다.
     */
    private void configureVoiceButton() {
        voiceButton.setAccessibleText("음성 명령 듣기");
        voiceButton.setAccessibleHelp("Alt와 V 키로도 음성 명령을 시작할 수 있습니다."
                + " 단추를 누른 뒤 말씀하시면 됩니다.");
        voiceButton.setOnAction(event -> startVoiceCommand());
    }

    /** 어느 화면에서도 같은 챗봇 대화로 들어가는 상단 고정 단추. */
    private void configureChatButton() {
        chatButton.setAccessibleText("AI 챗봇 열기");
        chatButton.setAccessibleHelp("Alt와 C 키로도 AI 챗봇을 열 수 있습니다.");
        chatButton.getStyleClass().add("top-chat-button");
        chatButton.setOnAction(event -> navigate(Screen.CHAT));
    }

    private void startVoiceCommand() {
        if (voiceController == null) {
            voiceController = new org.ossproject.desktop.voice.VoiceCommandController(
                    voiceInput, java.util.concurrent.ForkJoinPool.commonPool(),
                    Platform::runLater, createVoiceActions());
            refreshVoiceVocabulary();
        }
        if (voiceController.listening()) return;
        voiceButton.setText("듣는 중");
        voiceController.listenOnce();
        // 단추 글씨는 결과가 오면 tell 에서 되돌린다.
    }

    /**
     * 인식기에 알아들을 종목을 알려 준다.
     *
     * <p>보유·관심 종목이 바뀌면 다시 부른다. 이것을 하지 않으면 인식기가 종목명을
     * 통째로 놓친다 — 실측에서 "카카오" 가 "다가오" 로 나왔고 파서도 못 고쳤다.
     */
    private void refreshVoiceVocabulary() {
        if (voiceController == null) return;
        java.util.LinkedHashMap<String, org.ossproject.voice.KnownStock> known =
                new java.util.LinkedHashMap<>();
        for (WatchlistItem item : session.watchlistItems()) {
            known.putIfAbsent(item.symbol(),
                    new org.ossproject.voice.KnownStock(item.symbol(), item.securityName()));
        }
        var selected = session.selectedStock();
        if (selected != null && selected.symbol() != null && !selected.symbol().isBlank()) {
            known.putIfAbsent(selected.symbol(),
                    new org.ossproject.voice.KnownStock(selected.symbol(), selected.name()));
        }
        voiceController.updateVocabulary(java.util.List.copyOf(known.values()));
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
        // Enter 를 팝업에서도 받는다.
        //
        // 추천 목록은 ContextMenu 라서 자기 창에서 키를 처리한다. 검색칸에 걸어 둔
        // 거르개는 그 경로에 없어서, 팝업이 떠 있는 동안 누른 Enter 는 검색칸까지
        // 오지 않는다. 실제로 추적을 걸어 보니 글자는 들어오는데 Enter 만 한 번도
        // 도달하지 않았고, 그래서 사용자는 팝업이 닫힐 때까지 Enter 를 여러 번 눌러야 했다.
        //
        // 목록 안에서 고른 것이 있으면 그것을 연다. 아직 아무것도 안 골랐으면 검색칸에
        // 친 말로 조회한다 — 사용자가 보고 있던 것은 자기가 친 글자다.
        globalSearchMenu.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ENTER) return;
            if (globalSearchSuggestions.isFocused()) {
                StockSearchItem picked = globalSearchSuggestions.getSelectionModel().getSelectedItem();
                if (picked != null) {
                    openGlobalSearchSuggestion(picked);
                    event.consume();
                    return;
                }
            }
            if (globalRecentSearches.isFocused()) {
                String recent = globalRecentSearches.getSelectionModel().getSelectedItem();
                if (recent != null) {
                    openGlobalRecentSearch(recent);
                    event.consume();
                    return;
                }
            }
            openSearchedStock();
            event.consume();
        });

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
        // 조합이 시작되면 팝업을 미루고, 글자가 확정되면 다시 연다.
        //
        // 기본 처리를 덮어쓰지 않도록 setOn... 이 아니라 addEventHandler 를 쓴다.
        // 덮어쓰면 한글 입력 자체가 동작하지 않는다.
        globalSearch.addEventHandler(javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                event -> {
                    composingHangul = !event.getComposed().isEmpty();
                    if (composingHangul) globalSearchDelay.stop();
                    else if (globalSearchPopupArmed) globalSearchDelay.playFromStart();
                });
        // 조합 표시가 끼면 추천이 영영 안 뜬다. 칸을 벗어나면 반드시 푼다.
        globalSearch.focusedProperty().addListener((observed, had, has) -> {
            if (!has) composingHangul = false;
        });
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
            } else if (event.getCode() == KeyCode.ENTER) {
                // Enter 는 여기서 끝낸다. 거르개(filter)는 칸의 기본 처리보다 먼저 도므로,
                // 한글 확정이 팝업을 다시 띄우기 전에 조회가 나간다. consume 해서
                // setOnAction 이 같은 일을 한 번 더 하지 않게 막는다.
                openSearchedStock();
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
        // 조합 중에 띄우면 포커스를 뺏겨 글자가 자모로 풀린다.
        if (composingHangul) return;
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
        // 깃발까지 내린다. 숨기기만 하면 곧바로 다시 뜬다 — Enter 로 한글이 확정되면서
        // 글자가 바뀌고, 그 리스너가 showGlobalSearchMenu() 를 부르기 때문이다. 팝업이
        // 다시 뜨면 초점이 그리로 가서, 사용자는 Enter 를 한 번 더 눌러야 움직인다.
        globalSearchPopupArmed = false;
        // 자동완성 타이머를 먼저 세운다. 세우지 않으면 이 조회가 나간 직후에 타이머가
        // 터져 더 새 조회가 되고, 방금 낸 조회는 "낡은 것"으로 밀려 조용히 버려진다.
        // 그러면 Enter 를 눌러도 아무 일도 일어나지 않는다.
        //
        // 한글에서 잘 난다. Enter 를 누르는 순간 마지막 음절이 확정되면서 입력 리스너가
        // 한 번 더 돌아 220밀리초 타이머가 새로 시작되기 때문이다. "삼성화재" 를 치고
        // Enter 를 눌렀는데 화면이 그대로였던 것이 이것이다.
        globalSearchDelay.stop();
        if (globalSearch.getText() == null || globalSearch.getText().isBlank()) {
            stockSearchViewModel.prepare("", "전체");
            screenController.invalidate(Screen.SEARCH);
            navigate(Screen.SEARCH);
            return;
        }
        String query = globalSearch.getText().trim();
        stockSearchViewModel.recordRecentQuery(query);
        status.setText(query + " 종목을 조회하고 있습니다.");
        // thenAccept 를 쓰면 안 된다. 조회가 예외로 끝나면 아예 실행되지 않아, 오류도
        // 메시지도 없이 화면이 그대로 있는다. 실제로 Enter 를 눌러도 아무 일이 일어나지
        // 않던 것이 이것이었다 — StockSearchViewModel 은 실패를 completeExceptionally
        // 로 알리는데, thenAccept 는 그 길을 보지 않는다.
        //
        // whenComplete 는 성공과 실패를 모두 받는다. 실패도 사용자에게는 결과다.
        stockSearchViewModel.filter(query, "전체").whenComplete((result, failure) ->
                Platform.runLater(() -> {
            if (failure != null || result == null) {
                String reason = failure == null ? "종목을 조회하지 못했습니다."
                        : "종목을 조회하지 못했습니다. " + rootMessageOf(failure);
                status.setText(reason);
                announce(reason, SpeechPriority.USER_REQUEST, "search-failed");
                play(SoundCue.ERROR);
                return;
            }
            // 더 새 조회에 밀렸다. 그래도 사용자는 Enter 를 눌렀다 — 아무 일도 일어나지
            // 않는 것이 제일 나쁘다. 소리로 쓰는 사용자에게는 앱이 멈춘 것과 구별되지
            // 않는다. 고를 수 없으면 목록으로라도 데려간다.
            if (!result.applied()) {
                screenController.invalidate(Screen.SEARCH);
                navigate(Screen.SEARCH);
                return;
            }
            if (!stockSearchViewModel.lastError().isBlank()) {
                screenController.invalidate(Screen.SEARCH);
                navigate(Screen.SEARCH);
                status.setText(stockSearchViewModel.lastError());
                return;
            }
            // 후보가 하나뿐이거나 종목코드가 정확히 일치하면 바로 연다.
            //
            // 종목명이 정확히 일치할 때도 바로 연다. 예전에는 "한화"처럼 다른 종목의
            // 앞부분과 겹칠 수 있다는 이유로 목록을 먼저 보여 주었는데, 그러면 이름을
            // 통째로 친 사람까지 한 번 더 눌러야 했다. 게다가 "한 번 더 누르세요" 라는
            // 안내가 조용한 상태 줄에만 적혀서, 소리로 쓰는 사용자에게는 아무 일도
            // 일어나지 않은 것처럼 보였다.
            //
            // 후보를 숨기지 않는다. 열면서 몇 건이 더 있는지 말로 함께 알린다. 알리는
            // 방식을 "강제로 목록에 세우기" 에서 "열고 나서 말해 주기" 로 바꾼 것이다.
            StockSearchItem unambiguous = result.count() == 1
                    ? stockSearchViewModel.items().get(0)
                    : stockSearchViewModel.exactSymbolMatch(query).orElse(null);
            var exact = stockSearchViewModel.exactMatch(query);
            if (unambiguous == null) {
                unambiguous = exact.orElse(null);
            }
            if (unambiguous != null) {
                stockSearchViewModel.setPreferredSymbol(null);
                stockSearchViewModel.select(unambiguous);
                navigate(Screen.STOCK_DETAIL);
                announceOtherMatches(unambiguous.name(), result.count());
                return;
            }

            stockSearchViewModel.setPreferredSymbol(exact.map(StockSearchItem::symbol).orElse(null));
            screenController.invalidate(Screen.SEARCH);
            navigate(Screen.SEARCH);
            // 목록으로 온 것은 앱이 고를 수 없었다는 뜻이다. 그 사실과 다음에 할 일을
            // 소리로도 전한다. 상태 줄은 조용한 Label 이라, 여기에만 적으면 소리로
            // 쓰는 사용자에게는 아무 일도 일어나지 않은 것처럼 보인다.
            // 결과 안내는 검색 화면이 한다. 여기서 적어 두면, 화면이 뜨면서 스스로
            // 다시 조회하고 그 결과로 상태 줄을 덮어써 사라진다.
        }));
    }

    /** 예외 사슬에서 사용자에게 보여 줄 말을 꺼낸다. 없으면 종류 이름이라도 남긴다. */
    private static String rootMessageOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
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

    /**
     * 연 종목 말고 다른 후보가 있으면 그 사실을 말한다.
     *
     * <p>바로 열어 주는 대신 치르는 값이다. 후보를 못 보고 지나치지 않도록, 몇 건이
     * 더 있는지와 어디서 볼 수 있는지를 함께 알린다.
     *
     * <p>화면 이름 안내보다 뒤에 오도록 같은 우선순위를 쓰되 열쇠를 달리한다. 두
     * 문장이 이어서 나가야 "삼성전자 상세 화면입니다. 이름이 비슷한 종목이 …" 로 읽힌다.
     */
    private void announceOtherMatches(String opened, int total) {
        if (total <= 1) {
            return;
        }
        String message = "이름이 비슷한 종목이 " + (total - 1) + "건 더 있습니다. "
                + "Alt+3 을 누르면 검색 목록을 봅니다.";
        status.setText(opened + "을(를) 열었습니다. " + message);
        announce(message, SpeechPriority.INFORMATION, "search-other-matches",
                SpeechMergePolicy.REPLACE_PENDING);
    }

    /**
     * 목록에서 지금 고른 것을 읽어 준다.
     *
     * <p>{@code requestSpeech} 를 쓰지 않는다. 그것은 사용자가 "읽어 줘" 라고 부른
     * 자리에 쓰는 것이라, 화면 읽기가 꺼져 있으면 "꺼져 있습니다" 라고 알린다.
     * 방향키를 누를 때마다 그 경고가 나가면 목록을 훑을 수가 없다. 여기서는 꺼져
     * 있으면 그냥 조용하다.
     *
     * <p>{@code REPLACE_PENDING} 이 핵심이다. 방향키를 빠르게 누르면 지나온 항목이
     * 줄줄이 쌓이는데, 그러면 손은 멈췄는데 소리는 한참 뒤처져 따라온다. 마지막에
     * 멈춘 것 하나만 읽는다.
     */
    private void announceListSelection(String text) {
        announce(text, SpeechPriority.INFORMATION, "list-selection",
                SpeechMergePolicy.REPLACE_PENDING);
    }

    /**
     * 조회 결과를 알린다.
     *
     * <p>목록 선택 안내와 열쇠를 나눈다. 같은 열쇠에 REPLACE_PENDING 을 걸어 두었더니,
     * 조회 결과를 말하려는 차례에 목록이 첫 행을 고르면서 그 안내가 결과를 밀어냈다.
     * 몇 건인지 듣기도 전에 종목 이름부터 들리는 셈이었다.
     */
    private void announceSearchOutcome(String text) {
        announce(text, SpeechPriority.INFORMATION, "search-outcome",
                SpeechMergePolicy.REPLACE_PENDING);
    }

    /**
     * 질문 목록으로 간다.
     *
     * <p>화면을 옮기지 않는다. 초점을 옮기는 단축키가 화면까지 바꿔 버리면, 잘못 눌렀을
     * 때 보고 있던 것을 잃는다. 질문 화면으로 가는 길은 Alt+9 로 따로 있다.
     */
    private void focusChatQuestions() {
        ChatScreenView view = chatScreenView;
        if (screenController.currentScreen().orElse(null) != Screen.CHAT || view == null) {
            String message = "질문 목록은 AI 챗봇 화면에 있습니다. Alt+9 로 엽니다.";
            status.setText(message);
            announce(message, SpeechPriority.INFORMATION, "question-focus",
                    SpeechMergePolicy.REPLACE_PENDING);
            return;
        }
        view.focusQuestions();
    }

    /**
     * 종목 고르개로 간다.
     *
     * <p>화면마다 고르개를 따로 들고 있지 않는다. 뉴스·닮은 차트·청각 차트·질문 화면이
     * 각자 만들고, 종목 상세처럼 아예 없는 화면도 있다. 어느 화면에 무엇이 있는지를
     * 장부로 관리하면 화면이 늘 때마다 그 장부를 고쳐야 하고, 빠뜨리면 조용히 안 듣는다.
     * 지금 보이는 화면에서 찾는다.
     *
     * <p>찾을 것은 고르개를 감싼 칸이 아니라 고르개 자체다. 감싼 칸은 초점을 받지
     * 못해서, 거기에 걸면 아무 일도 일어나지 않는다 — 실제로 그래서 안 들었다.
     */
    private void focusStockPicker() {
        javafx.scene.Node picker = findVisibleByStyleClass(root, "stock-picker");
        if (picker == null) {
            String message = "이 화면에는 종목 고르개가 없습니다.";
            status.setText(message);
            announce(message, SpeechPriority.INFORMATION, "picker-focus",
                    SpeechMergePolicy.REPLACE_PENDING);
            return;
        }
        picker.requestFocus();
        String name = picker.getAccessibleText();
        announce(name == null || name.isBlank() ? "종목 선택" : name,
                SpeechPriority.INFORMATION, "picker-focus", SpeechMergePolicy.REPLACE_PENDING);
    }

    /**
     * 가장 최근 챗봇 답을 읽어 준다.
     *
     * <p>화면을 옮기지 않는다. 대화 기록은 앱 어디서든 하나로 이어지므로, 다른 화면을
     * 보다가도 방금 받은 답을 다시 들을 수 있어야 한다.
     *
     * <p>답이 없으면 그 사실을 말한다. 아무 소리도 나지 않으면 단축키가 고장 난 것인지
     * 들을 것이 없는 것인지 구별할 수 없다.
     */
    private void listenToLatestChatAnswer() {
        ChatScreenView view = chatScreenView;
        if (view == null || !view.hasAnswer()) {
            String message = "아직 받은 답변이 없습니다. Alt+9 로 AI 챗봇을 열고 질문을 고르세요.";
            status.setText(message);
            announce(message, SpeechPriority.INFORMATION, "chat-answer-listen",
                    SpeechMergePolicy.REPLACE_PENDING);
            return;
        }
        view.listenToLatestAnswer();
    }

    /** 지금 보이는 화면에서 그 style class 를 단 첫 노드. 숨은 화면은 건너뛴다. */
    private static javafx.scene.Node findVisibleByStyleClass(javafx.scene.Node node, String styleClass) {
        if (node == null || !node.isVisible()) {
            return null;
        }
        if (node.getStyleClass().contains(styleClass)) {
            return node;
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            return findVisibleByStyleClass(scroll.getContent(), styleClass);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.Node found = findVisibleByStyleClass(child, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 주문 표를 든 줄 수에 맞춘다.
     *
     * <p>높이를 못 박아 두면 주문이 몇 건 안 되어도 빈 칸이 남고, 많으면 표 안에서
     * 끌어 내려야 한다. 화면 안에 또 스크롤 칸이 생기면 어느 것을 굴리고 있는지
     * 알기 어렵다 — 이상 감지 목록에서와 같은 이유다.
     */
    private void sizeOrderTableToRows(TableView<ObservableList<String>> table) {
        // setFixedCellSize(32) 를 무조건 걸어 두면 큰 글자 CSS의 56px 행 높이가
        // 무시되어 글자의 위아래가 잘린다. 현재 글자 모드와 같은 높이로 표의 실제 행과
        // 전체 높이를 함께 계산한다.
        double rowHeight = accessibility.largeTextEnabled()
                ? LARGE_ORDER_ROW_HEIGHT : ORDER_ROW_HEIGHT;
        double headerHeight = accessibility.largeTextEnabled()
                ? LARGE_ORDER_HEADER_HEIGHT : ORDER_HEADER_HEIGHT;
        table.setFixedCellSize(rowHeight);
        Runnable resize = () -> {
            int rows = Math.max(1, table.getItems().size());
            double height = headerHeight + rows * rowHeight + 2;
            table.setMinHeight(height);
            table.setPrefHeight(height);
            table.setMaxHeight(height);
        };
        table.getItems().addListener((javafx.collections.ListChangeListener<ObservableList<String>>)
                change -> resize.run());
        resize.run();
    }

    private void showScreen(Screen screen) {
        if (screen == Screen.WATCHLIST) watchlistViewModel.refresh();
        if (screen == Screen.STOCK_DETAIL || screen == Screen.TRADING) screenController.invalidate(screen);
        screenController.show(screen);
        announceScreen(screen);
    }

    /**
     * 화면이 바뀐 것을 알린다.
     *
     * <p>지금까지는 아무 소리도 나지 않았다. 상태 줄에 글을 적기는 했지만 그것은
     * 조용한 Label 이고, JavaFX 에는 live region 이 없어 스크린리더가 읽어 주지 않는다.
     * 그래서 Enter 를 누른 뒤 넘어간 것인지 아무 일도 없었던 것인지 알 수 없었다.
     *
     * <p>같은 화면을 다시 열 때는 말하지 않는다. 목록에서 항목을 오갈 때마다 같은
     * 문장이 되풀이되면 정작 들어야 할 말이 묻힌다.
     *
     * <p>{@code INFORMATION} 으로 보낸다. 주문 결과나 연결 끊김 같은 급한 말이 먼저
     * 나가야 하고, 화면 이름 때문에 그것이 밀리면 안 된다. 그리고 {@code REPLACE_PENDING}
     * 이라 화면을 연달아 넘기면 마지막 것만 읽는다 — 지나온 화면 이름을 다 듣고
     * 있을 이유가 없다.
     */
    private void announceScreen(Screen screen) {
        if (screen == lastAnnouncedScreen) {
            return;
        }
        lastAnnouncedScreen = screen;
        String message = screenAnnouncement(screen);
        status.setText(message);
        announce(message, SpeechPriority.INFORMATION, "screen-change",
                SpeechMergePolicy.REPLACE_PENDING);
    }

    /** 화면 이름에 무엇을 보고 있는지까지 붙인다. "종목 상세" 만으로는 어느 종목인지 모른다. */
    private String screenAnnouncement(Screen screen) {
        return switch (screen) {
            case STOCK_DETAIL -> session.selectedStock().name() + " 상세 화면입니다.";
            case TRADING -> session.selectedStock().name() + " 주문 화면입니다.";
            case SIMILAR -> session.selectedStock().name() + " 닮은 차트 화면입니다.";
            case NEWS -> session.selectedStock().name() + " 뉴스 화면입니다.";
            case RADIO -> session.selectedStock().name() + " 청각 차트 화면입니다.";
            default -> screen.label() + " 화면입니다.";
        };
    }

    /** 앱 화면을 만들기 시작할 때 시장 지표 요청도 바로 시작해 한 번만 재사용한다. */
    private void preloadMarketOverview() {
        if (startupMarketOverview != null) return;
        startupMarketOverview = CompletableFuture.supplyAsync(marketOverview::overview)
                .handle((indices, failure) -> failure == null && indices != null
                        ? List.copyOf(indices) : List.of());
    }

    /**
     * 홈 카드가 쓸 시장 지표.
     *
     * <p>빈 목록을 굳히지 않는다. AI 서비스는 앱이 직접 띄우므로 첫 요청이 그 부팅을
     * 앞지를 수 있다. 그때 실패를 캐시에 남기면 서비스가 몇 초 뒤 올라와도 홈은
     * "받지 못했습니다" 로 남는다 — 앱을 껐다 켜야만 풀렸다. 실제로 그랬다.
     *
     * <p>기다리는 시간도 묶는다. 시장 조회는 60초까지 잡혀 있는데 여기는 화면
     * 스레드다. 늦으면 일단 비운 채로 그리고, 값이 도착하면 스스로 다시 그린다.
     */
    private List<org.ossproject.ai.MarketIndex> preparedMarketOverview() {
        preloadMarketOverview();
        List<org.ossproject.ai.MarketIndex> loaded;
        try {
            loaded = startupMarketOverview.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            loaded = List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            loaded = List.of();
        } catch (java.util.concurrent.ExecutionException failure) {
            loaded = List.of();
        }
        if (loaded.isEmpty()) {
            retryMarketOverview();
        }
        return loaded;
    }

    /**
     * 지표를 못 받았으면 뒤에서 한 번 더 받아 오고, 받으면 홈을 다시 그린다.
     *
     * <p>홈은 상태를 보존하는 화면이라 스스로 다시 그리지 않는다. 값만 고쳐 두면
     * 사용자가 다른 화면에 다녀와야 보인다.
     */
    private void retryMarketOverview() {
        if (marketOverviewRetrying) {
            return;
        }
        marketOverviewRetrying = true;
        retryMarketOverview(MARKET_OVERVIEW_ATTEMPTS);
    }

    /**
     * AI 서버가 열릴 때까지 몇 번 더 두드린다.
     *
     * <p>서버는 모델을 읽고 지수를 받은 뒤에야 포트를 연다. 로그를 보니 그 준비에
     * 10초쯤 걸렸다. 앱이 켜지자마자 보낸 첫 요청은 그 사이에 떨어지므로, 한 번 더
     * 시도하는 것으로는 모자란다. 3초 간격으로 여덟 번까지 — 30초 가까이 기다린다.
     *
     * <p>받으면 곧바로 멈춘다. 못 받으면 조용히 그만둔다. 홈은 "받지 못했습니다" 로
     * 남고, 그것은 사실이다.
     */
    private void retryMarketOverview(int attemptsLeft) {
        CompletableFuture.supplyAsync(marketOverview::overview)
                .handle((indices, failure) -> failure == null && indices != null
                        ? List.copyOf(indices) : List.<org.ossproject.ai.MarketIndex>of())
                .thenAccept(indices -> Platform.runLater(() -> {
                    if (!indices.isEmpty()) {
                        marketOverviewRetrying = false;
                        startupMarketOverview = CompletableFuture.completedFuture(indices);
                        screenController.invalidate(Screen.DASHBOARD);
                        if (screenController.currentScreen().orElse(null) == Screen.DASHBOARD) {
                            screenController.show(Screen.DASHBOARD);
                        }
                        return;
                    }
                    if (attemptsLeft <= 1) {
                        marketOverviewRetrying = false;
                        return;
                    }
                    javafx.animation.PauseTransition wait =
                            new javafx.animation.PauseTransition(MARKET_OVERVIEW_RETRY_DELAY);
                    wait.setOnFinished(event -> retryMarketOverview(attemptsLeft - 1));
                    wait.play();
                }));
    }

    private javafx.scene.Node createAccessibleChartScreen() {
        StockDetailViewModel.ChartRange audibleRange = StockDetailViewModel.ChartRange.DAY;
        if (stockDetailViewModel.hasCurrentChartData(audibleRange)) {
            try {
                return rebuildAccessibleChart(audibleRange).root();
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

        java.util.concurrent.CompletionStage<?> loadingStage = stockDetailViewModel.hasCurrentDetail()
                ? stockDetailViewModel.loadHistory(audibleRange)
                : stockDetailViewModel.loadInitial();
        loadingStage.whenComplete((data, failure) -> {
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
                AccessibleChartView view = rebuildAccessibleChart(audibleRange);
                // 안내 문구를 가운데 두려고 준 여백이다. 차트가 들어오면 그 여백은
                // 차트 자신의 여백 위에 한 번 더 얹혀, 처음 들어왔을 때만 본문이
                // 안쪽으로 밀렸다 — 다른 화면에 다녀오면 이 자리를 거치지 않아
                // 같은 화면이 다르게 보였다.
                placeholder.setPadding(Insets.EMPTY);
                placeholder.getStyleClass().remove("screen-content");
                placeholder.getChildren().setAll(view.root());
                screenController.focusContent();
                status.setText(session.selectedStock().name() + " 청각 차트를 준비했습니다.");
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

    private AccessibleChartView rebuildAccessibleChart(StockDetailViewModel.ChartRange range) {
        if (accessibleChartController != null) {
            sonificationPreferences = accessibleChartController.preferences();
            accessibleChartController.close();
        }
        List<Candle> candles = stockDetailViewModel.candles(range);
        String seriesDescription = range.label() + "봉";
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
    /** 탭 머리가 차지하는 높이. 호가 칸 최소 높이를 셈할 때 더한다. */
    private static final double TAB_HEADER_HEIGHT = 34;

    private javafx.scene.Node createOrderBookPanel(String stockName) {
        return createOrderBookPanel(stockName, null);
    }

    private javafx.scene.Node createOrderBookPanel(
            String stockName, java.util.function.Consumer<BigDecimal> onPriceSelected) {
        OrderBookLadderView ladder = new OrderBookLadderView(stockName);
        ladder.setOnPriceSelected(onPriceSelected);
        DepthChartCanvas depth = new DepthChartCanvas();
        // 표가 원본이고 그래프는 보조다. 차트 탭과 같은 순서로 둔다.
        TabPane views = new TabPane(tab("호가 표", ladder.root()), tab("누적 깊이 그래프", depth));
        views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        // 높이를 고정하면 호가 표가 잘려 아래 단계에 아예 닿을 수 없다. 내용에 맞춰 늘어나게
        // 두고, 화면이 길어지면 바깥 스크롤로 닿는다.
        //
        // 다만 pref 만으로는 부족하다. 주문 화면에서는 이 칸이 SplitPane 안에 들어가는데,
        // SplitPane 은 자식의 pref 를 무시하고 제 기본 높이를 쓴다. 그래서 실제로 아래
        // 단계가 잘렸다. 사다리가 알려 주는 필요 높이를 최소 높이로 건다.
        // 최소는 쓸 만한 만큼만 요구하고, 기준 높이로 모든 단계를 청한다. 자리가 있으면
        // 전부 펼쳐지고, 없으면 표 안에서 스크롤된다. 최소로 전부를 요구했더니 큰 글자
        // 모드에서 표가 창보다 길어져 맨 아래 단계가 스크롤도 없이 잘렸다.
        views.minHeightProperty().bind(ladder.requiredHeight().add(TAB_HEADER_HEIGHT));
        views.prefHeightProperty().bind(ladder.preferredHeight().add(TAB_HEADER_HEIGHT));
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
        openOrder(side, 1);
    }

    /** 수량까지 정해 주문 화면을 연다. 말로 주문할 때 쓴다. */
    private void openOrder(OrderSide side, int quantity) {
        pendingOrderQuantity = Math.max(1, quantity);
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
                OrderType.LIMIT, pendingOrderQuantity, price, origin);
        // 다음 주문이 지난번 수량을 물려받으면 안 된다. 말로 열 주를 주문한 뒤
        // 단추로 연 주문 화면이 열 주로 차 있으면 사용자는 그것을 모른 채 누른다.
        pendingOrderQuantity = 1;
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
                case CHAT -> "AI 챗봇";
                default -> screen.label();
            };
            currentLocation.setText(location);
            currentLocation.setAccessibleText("현재 화면 " + location);
            Platform.runLater(() -> applyKeyboardGuidance(accessibility.keyboardGuidanceEnabled()));
        });
        screenController.registerPreservingState(Screen.DASHBOARD, this::createDashboard);
        screenController.register(Screen.CONNECTION, this::createConnectionScreen);
        screenController.registerPreservingState(Screen.SEARCH, this::createSearchScreen);
        screenController.register(Screen.STOCK_DETAIL, this::createStockScreen);
        screenController.registerPreservingState(Screen.WATCHLIST, this::createWatchlistScreen);
        screenController.register(Screen.TRADING, this::createTradingScreen);
        screenController.register(Screen.ACCOUNT, this::createAccountScreen);
        screenController.registerPreservingState(Screen.ANOMALY, this::createAnomalyScreen);
        screenController.registerPreservingState(Screen.NOTIFICATIONS,
                () -> new NotificationsScreenView(session.notifications(), status::setText,
                        this::scheduleStateSave, this::requestSpeech).create());
        // 같은 종목으로 돌아올 때 수 초 걸리는 AI 분석을 다시 시작하지 않는다. 종목
        // 고르개가 값을 바꾸면 withStockPicker 안에서 명시적으로 invalidate 한다.
        screenController.registerPreservingState(Screen.SIMILAR,
                () -> withStockPicker(Screen.SIMILAR, createSimilarScreen()));
        screenController.register(Screen.NEWS,
                () -> withStockPicker(Screen.NEWS, createNewsScreen()));
        screenController.registerPreservingState(Screen.CHAT, this::createChatScreen);
        screenController.register(Screen.RADIO,
                () -> withStockPicker(Screen.RADIO, createAccessibleChartScreen()));
        screenController.registerPreservingState(Screen.SETTINGS, this::createSettingsScreen);
    }

    private VBox createDashboard() {
        return new DashboardScreenView(
                tradingUseCase::account, this::preparedMarketOverview, this::requestSpeech,
                this::navigationIcon, this::openNavigationScreen).create();
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
        ScrollPane scroll = new ScrollPane(host);
        scroll.setFitToWidth(true);
        // 높이를 맞추면 내용이 늘어나지 못해 잘린다. 넘치면 스크롤되게 둔다.
        scroll.setFitToHeight(false);
        scroll.setAccessibleText("주문 화면");
        scroll.getStyleClass().add("workspace-scroll");
        useBrowserLikeScrolling(scroll);

        CompletableFuture.supplyAsync(tradingUseCase::orders).whenComplete((orders, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        progress.setVisible(false);
                        loading.setText("주문 상태를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
                    } else {
                        host.getChildren().setAll(createTradingScreenContent(orders));
                        host.setAlignment(Pos.TOP_LEFT);
                        host.requestLayout();
                        // 로딩 중 ScrollPane 이 받았던 초점·스크롤 위치를 그대로 두면 교체된
                        // 긴 본문의 중간(호가 표)부터 보일 수 있다. 배치가 끝난 다음 제목으로
                        // 돌려 사용자가 화면의 시작을 놓치지 않게 한다.
                        Platform.runLater(() -> {
                            scroll.setHvalue(scroll.getHmin());
                            scroll.setVvalue(scroll.getVmin());
                        });
                    }
                }));
        return scroll;
    }

    private VBox createTradingScreenContent(List<Order> orders) {
        StockDetail selectedDetail = stockDetailViewModel.detail();
        PreparedOrderForm preparedOrderForm = createOrderForm();
        Node orderBook = createOrderBookPanel(selectedDetail.name(), selectedPrice -> {
            if (preparedOrderForm.view().selectLimitPrice(selectedPrice)) {
                status.setText("지정가를 " + stockDetailViewModel.formatPrice(selectedPrice)
                        + "으로 맞췄습니다.");
            } else {
                status.setText("시장가 주문은 가격을 직접 선택하지 않습니다. 주문 유형을 지정가로 바꿔주세요.");
            }
        });
        VBox orderForm = preparedOrderForm.root();
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
        // 상한을 190 으로 묶어 두면 안에 든 배너·표·취소 단추가 그 안에 다 못 들어가
        // 단추가 잘린다. 잘린 단추는 누를 수 없고, 이 화면에서만 주문을 취소할 수 있다.
        // 화면 전체가 스크롤되므로 여기서 높이를 묶을 이유가 없다.
        // 표를 든 만큼만 키워, 안에서 끌어 내리지 않고 한눈에 본다. 화면 전체가
        // 스크롤되므로 길어져도 닿지 못하는 곳이 생기지 않는다.
        sizeOrderTableToRows(openOrders);
        sizeOrderTableToRows(fills);
        statusTabs.setMinHeight(200);
        statusTabs.setMaxHeight(Double.MAX_VALUE);
        statusTabs.getStyleClass().add("order-status-tabs");

        VBox body = new VBox(10, orderArea, sectionHeading("주문 상태"), statusTabs);
        body.getStyleClass().addAll("screen-content", "trading-screen");
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0);
        VBox.setVgrow(orderArea, Priority.ALWAYS);
        return body;
    }

    /**
     * 종목 상세 화면.
     *
     * <p>여기서는 조립만 한다. 차트와 호가와 체결은 각자 클래스가 있고, 이 메서드는 그것을
     * 만들어 넘긴다.
     */
    private VBox createStockScreen() {
        StockDetail detail = stockDetailViewModel.detail();
        StockSelection selection = stockDetailViewModel.selection();
        pendingOrderPrice = stockDetailViewModel.plainOrderPrice();

        Button favorite = new WatchlistToggle(session.watchlistItems(), detail.symbol(),
                selection.exchange(), detail.name(),
                () -> addToWatchlistBySymbol(detail.symbol(), detail.name()),
                () -> removeFromWatchlist(detail.symbol(), selection.exchange(), detail.name()))
                .button();

        StockChartPanel chartPanel = new StockChartPanel(detail.name(),
                stockDetailViewModel::formatPrice,
                stockDetailViewModel::loadHistory,
                stockDetailViewModel::loadOlderHistory,
                this::startLiveChart,
                status::setText,
                () -> play(SoundCue.ERROR),
                () -> navigate(Screen.RADIO));

        // 호가와 체결은 키움 조회 뒤 실시간 스트림으로 이어 붙인다. 기업정보·거래원·
        // 프로그램매매는 아직 실제 TR 이 없으므로 완성된 기능처럼 탭을 노출하지 않는다.
        return new StockScreenView(detail, selection.exchange(),
                stockDetailViewModel::formatPrice, favorite,
                () -> openOrder(OrderSide.BUY), () -> openOrder(OrderSide.SELL),
                this::requestSpeech)
                .create(chartPanel.create(
                                stockDetailViewModel.history(StockDetailViewModel.ChartRange.DAY)),
                        createOrderBookPanel(detail.name()),
                        createTradeTapePanel(detail.name()));
    }

    private VBox createSearchScreen() {
        return new SearchScreenView(stockSearchViewModel, this::navigate, status::setText,
                this::announceListSelection, this::announceSearchOutcome).create();
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

        javafx.scene.Node pickerRoot = picker.root();
        if (screen == Screen.SIMILAR || screen == Screen.NEWS) {
            pickerRoot.getStyleClass().add("analysis-stock-picker-row");
        } else if (screen == Screen.RADIO) {
            pickerRoot.getStyleClass().add("radio-stock-picker-row");
        }
        VBox host = new VBox(10, pickerRoot, content);
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

    /**
     * 닮은 종목을 받아 온다.
     *
     * <p>준비 여부를 먼저 묻되 화면 스레드에서 묻지 않는다. {@code available()} 은
     * AI 서버의 /health 를 동기로 부르는데, 그 서버는 모델과 지수를 다 읽은 뒤에야
     * 답한다. 화면 스레드에서 기다리면 그 시간만큼 창 전체가 멈춘다 — 메뉴를 누른
     * 순간의 렉이 이것이었다.
     *
     * <p>먼저 "불러오는 중" 을 그려 두고, 묻는 일과 받는 일은 뒤에서 한다.
     */
    private void loadSimilar() {
        SimilarScreenView view = similarView;
        if (view == null) {
            return;
        }
        view.loading();
        SecurityId requested = session.selectedStock().securityId();
        CompletableFuture.supplyAsync(aiInsightViewModel::available)
                .whenComplete((ready, probeFailure) -> Platform.runLater(() -> {
                    // 그새 다른 종목으로 화면을 다시 만들었으면 늦게 온 결과를 버린다.
                    if (similarView != view) {
                        return;
                    }
                    if (probeFailure != null || !Boolean.TRUE.equals(ready)) {
                        // 서버는 모델을 읽고 지수를 받은 뒤에야 포트를 연다. 그 사이의
                        // 연결 거부를 실패로 적으면 사용자는 고칠 수 없는 문제로 읽고
                        // 포기한다.
                        view.unavailable(aiServiceProcess != null && aiServiceProcess.running()
                                ? "AI 서버를 준비하고 있습니다. 10초쯤 걸립니다."
                                : aiInsightViewModel.unavailableReason());
                        return;
                    }
                    aiInsightViewModel.analyze(requested, true,
                            insight -> {
                                lastInsight = insight;
                                lastInsightSecurity = requested;
                                view.show(insight);
                            },
                            view::unavailable);
                }));
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
                () -> removeFromWatchlist(symbol, "", name)).button();
    }

    /** 관심종목에서 뺀다. 빼기는 곧바로 끝나므로 그 자리에서 알린다. */
    private void removeFromWatchlist(String symbol, String exchange, String name) {
        if (stockSearchViewModel.removeFromWatchlist(symbol, exchange)) {
            scheduleStateSave();
            status.setText(name + "을 관심종목에서 뺐습니다.");
        }
    }

    /**
     * 종목 코드로 관심 목록에 담는다.
     *
     * <p>조회로 식별 정보를 채운 뒤 담는다. 코드와 이름만으로 만들면 거래소를
     * 추측하게 되어 KRX와 NXT 종목을 잘못 구분할 수 있다.
     *
     * <p>조회는 시간이 걸린다. 결과를 기다리지 않고 참을 돌려주면 단추가 담긴 것처럼
     * 바뀌었다가 되돌아간다. 그래서 여기서 기다린다.
     */
    private void addToWatchlistBySymbol(String symbol, String name) {
        status.setText(name + " 종목을 확인하고 있습니다.");
        // 결과를 기다리지 않는다. findBestMatch 는 화면 스레드에서 완료되므로 여기서
        // 기다리면 화면 스레드가 자기가 실행할 일을 기다리다 굳는다. 실제로 그렇게
        // 만들었다가 담았다 뺀 종목을 다시 담을 수 없었다.
        stockSearchViewModel.findBestMatch(symbol).thenAccept(item -> {
            if (item == null) {
                status.setText(name + " 종목 정보를 찾지 못했습니다.");
                return;
            }
            if (stockSearchViewModel.addToWatchlist(item)) {
                scheduleStateSave();
                status.setText(name + "을 관심종목에 담았습니다.");
            } else {
                status.setText(name + "은 이미 관심종목에 있습니다.");
            }
        }).exceptionally(failure -> {
            Platform.runLater(() -> status.setText(name + " 종목 정보를 받지 못했습니다."));
            return null;
        });
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

    /** 선택한 종목의 뉴스 화면. */
    private javafx.scene.Node createNewsScreen() {
        StockSelection selected = session.selectedStock();
        newsView = new NewsScreenView(selected.name(), this::requestSpeech, this::loadNews,
                url -> getHostServices().showDocument(url));
        javafx.scene.Node node = newsView.create();
        loadNews();
        return node;
    }

    /** 앱 어디서든 같은 기록으로 이어 쓰는 전역 챗봇 화면. */
    /**
     * 질문 화면.
     *
     * <p>종목 고르개를 함께 단다. 답은 고른 종목에 대한 것인데, 화면에 그 종목이 적혀
     * 있지 않으면 무엇에 대한 답인지 알 수 없다.
     *
     * <p>다른 화면과 달리 종목이 바뀌어도 화면을 다시 만들지 않는다. 대화 기록이 하나로
     * 이어지는 화면이라 다시 만들면 그동안의 문답이 사라진다. 물어보는 시점의 종목을
     * 그때 읽으므로 다시 만들 이유도 없다. 대신 기록에 바뀐 자리를 남긴다.
     */
    private javafx.scene.Node createChatScreen() {
        ChatScreenView view = new ChatScreenView(this::requestSpeech, this::askGlobalChat);
        view.setFocusSpeaker(this::announceListSelection);
        chatScreenView = view;
        javafx.scene.Node content = view.create();

        StockPicker picker = new StockPicker(session.watchlistItems(), session.selectedStock(),
                selected -> {
                    session.selectStock(selected);
                    view.noteStock(selected.name());
                    status.setText(selected.name() + "에 대해 답합니다.");
                });
        loadHoldingsInto(picker);
        picker.root().getStyleClass().add("analysis-stock-picker-row");

        VBox host = new VBox(10, picker.root(), content);
        host.setFillWidth(true);
        host.setMinSize(0, 0);
        VBox.setVgrow(content, Priority.ALWAYS);
        return host;
    }

    /** 질문 시점의 최신 앱 분석을 근거로 쓰되 대화 화면과 기록은 종목마다 나누지 않는다. */
    private void askGlobalChat(String question,
                               java.util.function.Consumer<org.ossproject.ai.ChatAnswer> onAnswer) {
        StockSelection selected = session.selectedStock();
        org.ossproject.ai.AiInsight current = selected.securityId().equals(lastInsightSecurity)
                ? lastInsight : null;
        if (current != null) {
            newsViewModel.ask(selected.securityId(), question, current, onAnswer);
            return;
        }
        // available() 도 /health 를 동기로 부른다. 질문을 누른 순간 창이 멈추면 안 된다.
        CompletableFuture.supplyAsync(aiInsightViewModel::available)
                .whenComplete((ready, probeFailure) -> Platform.runLater(() -> {
                    if (probeFailure != null || !Boolean.TRUE.equals(ready)) {
                        newsViewModel.ask(selected.securityId(), question, null, onAnswer);
                        return;
                    }
                    askWithFreshInsight(selected, question, onAnswer);
                }));
    }

    /** 분석을 새로 받아 그 근거로 답한다. 못 받으면 근거 없이 답한다. */
    private void askWithFreshInsight(StockSelection selected, String question,
                                     java.util.function.Consumer<org.ossproject.ai.ChatAnswer> onAnswer) {
        aiInsightViewModel.analyze(selected.securityId(), false, insight -> {
            lastInsight = insight;
            lastInsightSecurity = selected.securityId();
            newsViewModel.ask(selected.securityId(), question, insight, onAnswer);
        }, reason -> newsViewModel.ask(selected.securityId(), question, null, onAnswer));
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
        return new AnomalyScreenView(
                session.notifications(), anomalySubscriptions.size(), tradingUseCase::account,
                aiInsightListCoordinator.createPanel(),
                () -> navigate(Screen.WATCHLIST), this::requestSpeech, status::setText,
                this::scheduleStateSave, this::showInformation).create();
    }

    /**
     * 모의주문 폼.
     *
     * <p>초안과 수량 셈은 뷰모델이 맡는다. 화면 안에 두면 "10퍼센트를 눌렀을 때 몇 주인가"
     * 를 검사할 수 없다. 주문은 되돌릴 수 없는 동작이라 그 셈이 틀리면 사용자가 의도하지
     * 않은 수량으로 주문한다.
     */
    private record PreparedOrderForm(VBox root, OrderFormView view) { }

    private PreparedOrderForm createOrderForm() {
        StockSelection selected = stockDetailViewModel.selection();
        OrderDraft draft = orderDraft == null
                ? new OrderDraft(selected.symbol(), selected.name(), OrderSide.BUY,
                        OrderType.LIMIT, 1, pendingOrderPrice, Screen.DASHBOARD)
                : orderDraft;
        orderDraft = draft;

        OrderDraftViewModel viewModel = new OrderDraftViewModel(draft, this::currentPriceIfKnown);
        OrderFormView view = new OrderFormView(viewModel, preventDuplicateOrders,
                stockDetailViewModel::formatPrice, this::previewOrder, status::setText,
                this::rememberOrderDraft, new OrderFormView.PriceShortcuts(
                        this::currentPriceIfKnown, this::bestAskIfKnown,
                        this::bestBidIfKnown, this::orderBookTickIfKnown));
        VBox form = view.create();

        // 계좌 조회는 화면 스레드를 막지 않는다. 도착하면 비율 단추가 열린다.
        CompletableFuture.supplyAsync(tradingUseCase::account).whenComplete((account, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        view.accountFailed();
                        return;
                    }
                    viewModel.setAccount(account);
                    view.accountLoaded();
                }));
        return new PreparedOrderForm(form, view);
    }

    /** 화면이 고친 초안을 앱도 든다. 주문 화면을 떠났다 돌아와도 값이 남는다. */
    private void rememberOrderDraft(OrderDraft updated) {
        orderDraft = updated;
        pendingOrderPrice = updated.price();
    }

    /**
     * 지금 아는 현재가. 조회 전이거나 실패했으면 비어 있다.
     *
     * <p>모르면 모른다고 한다. 0 을 돌려주면 시장가 비율 단추가 "살 수 있는 수량이 없다"
     * 가 아니라 엉뚱한 수량을 내놓는다.
     */
    private java.util.Optional<BigDecimal> currentPriceIfKnown() {
        try {
            return java.util.Optional.ofNullable(stockDetailViewModel.detail())
                    .map(StockDetail::currentPrice);
        } catch (RuntimeException notLoaded) {
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<BigDecimal> bestAskIfKnown() {
        return orderBookViewModel.currentView().flatMap(view -> view.rows().stream()
                .filter(row -> row.askSize() > 0).map(org.ossproject.finance.model.orderbook.PriceLadderRow::price)
                .min(BigDecimal::compareTo));
    }

    private java.util.Optional<BigDecimal> bestBidIfKnown() {
        return orderBookViewModel.currentView().flatMap(view -> view.rows().stream()
                .filter(row -> row.bidSize() > 0).map(org.ossproject.finance.model.orderbook.PriceLadderRow::price)
                .max(BigDecimal::compareTo));
    }

    private java.util.Optional<BigDecimal> orderBookTickIfKnown() {
        return orderBookViewModel.currentView().flatMap(view -> {
            for (int index = 1; index < view.rows().size(); index++) {
                BigDecimal gap = view.rows().get(index - 1).price()
                        .subtract(view.rows().get(index).price()).abs();
                if (gap.signum() > 0) return java.util.Optional.of(gap);
            }
            return java.util.Optional.empty();
        });
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
                this::availableSpeechVoices,
                microphone::devices);
        SettingsScreenView.Actions actions = new SettingsScreenView.Actions(
                this::applyAccessibility,
                value -> {
                    preventDuplicateOrders = value;
                    scheduleStateSave();
                },
                this::previewSpeechSettings,
                this::auditCurrentScreen,
                this::navigate,
                status::setText,
                microphone::monitorLevel);
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

    private void showJournalDialog(JournalEntry existing) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle(existing == null ? "매매일지 작성" : "매매일지 수정");
        styleDialog(dialog);
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
        styleDialog(confirmation);
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
        styleDialog(confirmation);
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
                screenController.invalidate(Screen.DASHBOARD);
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
        styleDialog(confirmation);
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
                screenController.invalidate(Screen.DASHBOARD);
            }));
        });
    }

    /**
     * 이동할 수 있는 화면에 번호를 매긴다.
     *
     * <p>글자 단축키만으로는 열네 화면을 덮지 못한다. 남는 글자를 억지로 붙이면
     * 외울 수 없는 목록이 되고, 화면을 볼 수 없는 사용자는 그것을 확인할 방법도 없다.
     *
     * <p>번호는 왼쪽 이동 칸에 보이는 차례와 같다. "다섯 번째 항목" 이 곧 Alt+5 다.
     * 열 번째부터는 Alt+Shift+1 처럼 이어 붙인다.
     */
    private void installScreenNumberShortcuts(Scene scene) {
        List<Screen> targets = java.util.Arrays.stream(Screen.values())
                .filter(Screen::shownInSidebar)
                .toList();
        KeyCode[] digits = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4,
                KeyCode.DIGIT5, KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8, KeyCode.DIGIT9};
        for (int i = 0; i < targets.size() && i < digits.length * 2; i++) {
            Screen screen = targets.get(i);
            KeyCode digit = digits[i % digits.length];
            KeyCodeCombination combination = i < digits.length
                    ? new KeyCodeCombination(digit, KeyCombination.ALT_DOWN)
                    : new KeyCodeCombination(digit, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN);
            scene.getAccelerators().put(combination, () -> openScreenByShortcut(screen));
        }
    }

    /**
     * 단축키로 화면을 연다.
     *
     * <p>메뉴를 눌렀을 때와 같은 길로 간다. 단축키만 {@code navigate} 로 직행시켰더니
     * 주문 화면이 메뉴로 들어갈 때보다 눈에 띄게 느렸다. 같은 곳으로 가는 두 가지 길이
     * 서로 다르게 움직이면, 어느 쪽이 맞는지 사용자가 알 수 없다.
     *
     * <p>검색만 다르다. 메뉴로 들어가는 것은 새로 찾겠다는 뜻이라 검색어를 비우지만,
     * 단축키는 보던 목록으로 돌아가는 길이라 결과를 그대로 둔다.
     */
    private void openScreenByShortcut(Screen screen) {
        if (screen == Screen.SEARCH) {
            navigate(screen);
            return;
        }
        openNavigationScreen(screen);
    }

    /** 화면 이동 단축키를 사람이 읽을 문장으로. 도움말과 스크린리더가 같은 것을 쓴다. */
    private static List<String> screenShortcutLines() {
        List<Screen> targets = java.util.Arrays.stream(Screen.values())
                .filter(Screen::shownInSidebar)
                .toList();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < targets.size() && i < 18; i++) {
            String keys = i < 9 ? "Alt+" + (i + 1) : "Alt+Shift+" + (i - 8);
            lines.add(keys + " · " + targets.get(i).label());
        }
        return lines;
    }

    /**
     * 단축키 전체를 펼친다.
     *
     * <p>목록을 화면에 적는 것으로 끝내지 않는다. 읽어 주기까지 해야 소리로만 쓰는
     * 사용자가 처음 한 번을 익힐 수 있다.
     */
    private void showShortcutHelp() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("화면 이동");
        lines.addAll(screenShortcutLines());
        lines.add("");
        lines.add("자주 쓰는 것");
        lines.add("Alt+S · 상단 검색칸으로 이동 (Alt+3 은 검색 화면을 엽니다)");
        lines.add("Alt+O · 매수 주문 열기");
        lines.add("Alt+V · 음성 명령 시작");
        lines.add("Alt+Q · 질문 목록으로 (AI 챗봇 화면에서)");
        lines.add("Alt+K · 종목 고르개로 (고르개가 있는 화면)");
        lines.add("Alt+L · 최근 챗봇 답변 듣기 (어느 화면에서든)");
        lines.add("Alt+X · 현재 TTS 음성 즉시 중단");
        lines.add("Alt+왼쪽 화살표 · 뒤로");
        lines.add("F6 / Shift+F6 · 화면 구역 사이 이동");
        lines.add("F1 · 이 목록");
        lines.add("");
        lines.add("목록과 표에서");
        lines.add("위아래 화살표 · 항목 이동, Enter · 열기");
        lines.add("");
        lines.add("종목 상세와 주문에서");
        lines.add("Alt+B · 매수, Alt+Shift+B · 매도");
        lines.add("Alt+W · 관심종목 담기·빼기 (종목 상세)");
        lines.add("Alt+P · 가격 입력칸, Alt+N · 수량 입력칸 (주문)");
        lines.add("가격칸 위아래 화살표 · 한 호가 조정, Ctrl+Enter · 주문 내용 검토");
        lines.add("");
        lines.add("탭 화면에서");
        lines.add("Ctrl+Tab / Ctrl+Shift+Tab · 다음 탭 / 이전 탭");
        lines.add("");
        lines.add("관심종목에서");
        lines.add("Delete · 제거, Ctrl+위아래 · 순서 변경, Ctrl+R · 최신 시세 조회");
        lines.add("");
        lines.add("뉴스와 닮은 차트에서");
        lines.add("위아래 화살표 · 결과 이동, Enter · 열기·비교, Space · 기사 요약 듣기");
        lines.add("Ctrl+R · 다시 조회");
        lines.add("");
        lines.add("질문 목록에서");
        lines.add("좌우 화살표 · 질문 이동, Home·End · 처음·끝, Enter · 묻기");
        lines.add("Ctrl+Enter · 고른 질문 묻기, Ctrl+L · 대화 기록으로 이동");
        lines.add("");
        lines.add("알림과 이상 감지에서");
        lines.add("위아래 화살표 · 신호 이동, Enter·Space · 선택 내용 듣기, Delete · 삭제");
        lines.add("알림: Ctrl+F · 필터, Ctrl+Enter · 읽음, Ctrl+Shift+R · 모두 읽음");
        lines.add("");
        lines.add("차트에서");
        lines.add("좌우 화살표 · 이동, Ctrl+좌우 · 크게 이동");
        lines.add("Ctrl+위아래 · 확대·축소, Shift+위아래 · 세로 축척");
        lines.add("PageUp·PageDown · 축척을 좁힌 뒤 위아래로 옮기기");
        lines.add("Home·End · 처음·최신");
        lines.add("");
        lines.add("청각 차트에서");
        lines.add("Space · 재생·일시정지, Enter · 멈춘 지점의 정확한 값");
        lines.add("R · 다시 듣기, S · 전체 요약, +/- · 재생 속도");

        String text = String.join(System.lineSeparator(), lines);
        // 소리로 들을 때는 가운뎃점과 줄바꿈이 읽히지 않는다. 쉼표와 마침표로 바꿔
        // 끊어 읽게 한다.
        String spoken = String.join(". ", lines).replace(" · ", ", ");
        requestSpeech("키보드 단축키 목록입니다. " + spoken, "shortcut-help");
        Label guide = new Label(text);
        guide.setWrapText(true);
        guide.setAccessibleText("키보드 단축키 목록. " + spoken);
        guide.setPadding(new Insets(4, 10, 4, 4));
        ScrollPane guideScroll = new ScrollPane(guide);
        guideScroll.setFitToWidth(true);
        guideScroll.setPrefViewportWidth(680);
        guideScroll.setPrefViewportHeight(560);
        guideScroll.setAccessibleText("키보드 단축키 전체 목록");
        useBrowserLikeScrolling(guideScroll);
        Alert help = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.OK);
        help.setTitle("키보드 단축키");
        help.setHeaderText("키보드 단축키");
        styleDialog(help);
        help.getDialogPane().setContent(guideScroll);
        help.getDialogPane().setPrefWidth(740);
        help.showAndWait();
    }

    private void showInformation(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        styleDialog(alert);
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
                // 계좌는 국내 모의투자라 보유 종목은 KRX다.
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
        try {
            anomalyAlertRepository.save(alert);
        } catch (RuntimeException persistenceFailure) {
            // 알림을 디스크에 못 남겼다고 화면·음성 경고까지 잃어서는 안 된다.
            LOGGER.log(System.Logger.Level.WARNING,
                    "이상 감지 이력을 저장하지 못했습니다: {0}", persistenceFailure.getMessage());
        }
        addNotification("이상 감지", severity + " · " + alert.explanation());
        status.setText("이상 감지: " + alert.explanation());
        announce(alert.explanation(), SpeechPriority.ALERT,
                "anomaly-" + alert.symbol() + '-' + alert.type());
        play(alert.severity() == AnomalySeverity.HIGH ? SoundCue.ANOMALY_HIGH : SoundCue.WARNING);
        if (screenController != null) screenController.invalidate(Screen.ANOMALY);
    }

    /** 손익 금액을 부호와 함께 표기한다. */

    /**
     * 주문 재확인 창을 연다.
     *
     * <p>화면 컨트롤이 아니라 초안을 받는다. 컨트롤을 여섯 개 넘겨받으면 이 메서드가 폼의
     * 생김새에 매여, 폼을 고칠 때마다 함께 고쳐야 한다.
     */
    private void previewOrder(OrderDraft draft) {
        try {
            BigDecimal referencePrice = stockDetailViewModel.detail().currentPrice();
            OrderCommand request = draft.type() == OrderType.MARKET
                    ? OrderCommand.market(draft.symbol(), draft.name(), draft.side(),
                            draft.quantity())
                    : OrderCommand.limit(draft.symbol(), draft.name(), draft.side(),
                            draft.quantity(),
                            new BigDecimal(draft.price().replace(",", "").trim()));
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
        styleDialog(confirmation);
        confirmation.setHeaderText(request.name() + " " + request.quantity() + "주 "
                + request.side().displayName() + " 주문을 제출하시겠습니까?");
        String orderPrice = request.type() == OrderType.MARKET ? "시장가" : Formatters.won(request.limitPrice());
        confirmation.setContentText("종목 코드: " + request.symbol() + "\n주문 가격: " + orderPrice
                + "\n예상 주문금액: " + Formatters.won(result.estimatedAmount()) + costLines(result)
                + "\n주문 후 예상 현금: " + Formatters.won(result.availableCashAfter())
                + "\n\n확인하면 키움 모의투자 서버로 주문을 전송합니다. 실전 주문은 아닙니다.");
        String spokenPreview = "주문 내용 검토. " + result.describe()
                + " 아직 주문을 제출하지 않았습니다. 제출하려면 모의 "
                + request.side().displayName() + " 제출 버튼을 선택하세요. 취소하려면 취소 버튼을 선택하세요.";
        confirmation.getDialogPane().setAccessibleText(spokenPreview);
        ButtonType submit = new ButtonType("모의 " + request.side().displayName() + " 제출", ButtonBar.ButtonData.OK_DONE);
        confirmation.getButtonTypes().setAll(submit, ButtonType.CANCEL);
        // 설정에서 화면 읽기 TTS를 켠 경우에만 읽는다. 같은 미리보기를 빠르게 다시 열면
        // 예전 초안이 대기열에 남지 않도록 가장 최근 내용으로 교체한다.
        announce(spokenPreview, SpeechPriority.ORDER, "order-preview", SpeechMergePolicy.REPLACE_PENDING);
        // Enter를 무심코 눌러 제출하지 않게 첫 초점은 취소에 둔다. 제출은 내용을 들은 뒤
        // 명시적으로 Tab/Shift+Tab으로 고르게 한다.
        javafx.scene.Node cancelButton = confirmation.getDialogPane().lookupButton(ButtonType.CANCEL);
        Platform.runLater(cancelButton::requestFocus);
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
                        screenController.invalidate(Screen.DASHBOARD);
                        announce(receiptMessage, SpeechPriority.ORDER, "order-" + receipt.orderId());
                        play(SoundCue.SUCCESS);
                        Alert completed = new Alert(Alert.AlertType.INFORMATION);
                        styleDialog(completed);
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
        styleDialog(alert);
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
        restoreAnomalyHistory();
        // 기동할 때도 같은 길을 쓴다. 여기서만 따로 적용하면 첫 실행과 이후가 갈라진다.
        accessibility = accessibilityPreferencesRepository.load();
        if (!speechQueue.isClosed()) {
            speechQueue.setOptions(accessibility.speechOptions());
        }
        // 저장해 둔 마이크를 기동할 때도 적용한다. 설정을 바꿀 때만 적용하면, 다음
        // 실행에서는 다시 기본 장치로 돌아가 아무 소리도 들어오지 않는다.
        microphone.useDevice(accessibility.microphoneName());
        sonificationPreferences = sonificationPreferencesRepository.load();
    }

    /** 이전 속성 파일의 이상 알림은 SQLite 원본으로 교체해 중복을 막는다. */
    private void restoreAnomalyHistory() {
        try {
            session.notifications().removeIf(
                    notification -> notification.contains(" · 이상 감지 · "));
            List<String> history = anomalyAlertRepository.findRecent(50).stream()
                    .map(DesktopApplication::anomalyHistoryNotification)
                    .toList();
            session.notifications().addAll(0, history);
        } catch (RuntimeException persistenceFailure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "이상 감지 이력을 읽지 못했습니다: {0}", persistenceFailure.getMessage());
        }
    }

    private static String anomalyHistoryNotification(AnomalyAlert alert) {
        String stamp = java.time.LocalDateTime.ofInstant(
                        alert.detectedAt(), java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        String severity = alert.severity() == AnomalySeverity.HIGH ? "높음" : "주의";
        return "이상 기록 · " + stamp + " · 이상 감지 · " + alert.stockName()
                + " · " + severity + " · " + alert.explanation();
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
    /**
     * 사용자가 "읽어 줘" 라고 부른 자리.
     *
     * <p>자동 읽어 주기 설정을 보지 않는다. "듣기" 라고 적힌 단추를 눌렀는데 경고음만
     * 나면 그 단추는 고장 난 것이다. 설정이 다루는 것은 <em>부르지 않아도</em> 읽어
     * 주는 일이고, 이것은 부른 것이다.
     *
     * <p>실제로 그 구분이 없어서 막다른 길이 생겼다. 자동 읽기가 시끄러워 끄면 "설명
     * 듣기" 도 함께 죽고, 그것을 살리려고 다시 켜면 또 시끄러워진다. 저시력 사용자가
     * 흔히 쓰는 조합이 바로 "자동은 끄고 부를 때만 듣기" 다.
     *
     * <p>음성 장치 자체를 못 쓰는 경우에만 알린다. 그때는 정말로 들을 수 없다.
     */
    private void requestSpeech(String text, String key) {
        lastSpoken = text;
        if (speechQueue.isClosed()) {
            status.setText("음성 장치를 쓸 수 없습니다. 설정에서 음성과 출력 장치를 확인해주세요.");
            play(SoundCue.WARNING);
            return;
        }
        speechQueue.announce(new SpeechRequest(text, SpeechPriority.USER_REQUEST, key,
                SpeechMergePolicy.KEEP_FIRST));
    }

    /** 어느 화면에서든 현재 낭독과 대기 중인 낭독을 함께 멈춘다. */
    private void stopSpeechNow() {
        if (!speechQueue.isClosed()) speechQueue.clear();
        // 큐 밖에서 재생 중인 플랫폼 합성이 있어도 멈추도록 포트에도 명시한다.
        speechPort.stop();
        setChartSpeechActive(false);
        status.setText("TTS 음성 안내를 중단했습니다.");
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
        boolean textSizeChanged = accessibility.largeTextEnabled() != updated.largeTextEnabled();
        accessibility = updated;
        if (!speechQueue.isClosed()) {
            speechQueue.setOptions(updated.speechOptions());
        }
        if (!updated.speechEnabled()) {
            speechQueue.clear();
        }
        microphone.useDevice(updated.microphoneName());
        applyKeyboardGuidance(updated.keyboardGuidanceEnabled());
        toggleClass("reduced-motion", updated.reducedMotionEnabled());
        toggleClass("large-text", updated.largeTextEnabled());
        toggleClass("high-contrast", updated.highContrastEnabled());
        applyInformationDensity(updated.informationDensity());
        scheduleStateSave();
        // 주문 표 높이는 데이터 행 수와 글자 크기를 함께 사용한다. 음성 명령으로 주문
        // 화면에서 큰 글자를 바로 전환한 경우에도 32px 표가 남지 않게 화면을 다시 만든다.
        if (textSizeChanged && screenController.currentScreen().orElse(null) == Screen.TRADING) {
            screenController.invalidate(Screen.TRADING);
            Platform.runLater(() -> screenController.show(Screen.TRADING));
        }
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
        try {
            persistence.close();
        } catch (Exception closeFailure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "SQLite 저장소를 닫지 못했습니다: {0}", closeFailure.getMessage());
        }
    }
    public static void main(String[] args) { launch(args); }
}
