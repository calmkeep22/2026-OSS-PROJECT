package org.ossproject.desktop.composition;

import org.ossproject.accessibility.infrastructure.sound.ToneSoundAdapter;
import org.ossproject.accessibility.infrastructure.speech.SpeechAdapterFactory;
import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechQueue;
import org.ossproject.accessibility.port.SoundPort;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.application.policy.OrderGuard;
import org.ossproject.application.policy.OrderLimitPolicy;
import org.ossproject.application.port.AccountPort;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.port.MarketDataStreamPort;
import org.ossproject.application.port.OrderBookQueryPort;
import org.ossproject.application.port.TradeQueryPort;
import org.ossproject.application.port.OrderLifecyclePort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.anomaly.AnomalyAlert;
import org.ossproject.anomaly.AnomalyAlertRepository;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.finance.model.order.FeeSchedule;
import org.ossproject.desktop.persistence.AccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.DesktopStateRepository;
import org.ossproject.desktop.persistence.PropertiesAccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.PropertiesDesktopStateRepository;
import org.ossproject.desktop.persistence.PropertiesSonificationPreferencesRepository;
import org.ossproject.desktop.persistence.SonificationPreferencesRepository;
import org.ossproject.kiwoom.query.KiwoomMarketAdapters;
import org.ossproject.sonification.javasound.PcmGraphSonificationAdapter;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.SecretStoreException;
import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.windows.SecretStoreFactory;
import org.ossproject.persistence.PersistentOrderLifecyclePort;
import org.ossproject.persistence.SqliteAnomalyAlertRepository;
import org.ossproject.persistence.SqliteDatabase;
import org.ossproject.persistence.SqliteOrderRepository;

import org.ossproject.ai.AiInsightPort;
import org.ossproject.voice.VoiceInputPort;
import org.ossproject.voice.javasound.MicrophoneCapture;
import org.ossproject.voice.http.HttpVoiceInputAdapter;
import org.ossproject.ai.http.HttpAiInsightAdapter;
import org.ossproject.desktop.ai.AiServiceProcess;
import java.net.URI;
import java.util.Optional;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

/**
 * Desktop composition root. This is the only place where the UI selects concrete adapters.
 */
public record DesktopServices(
        TradingUseCase trading,
        MarketApplicationPort market,
        StockQueryPort stocks,
        CandleQueryPort candles,
        SpeechPort speech,
        SpeechQueue speechQueue,
        SoundPort sounds,
        SonificationPort sonification,
        SecretStore secrets,
        DesktopStateRepository stateRepository,
        AccessibilityPreferencesRepository accessibilityPreferences,
        SonificationPreferencesRepository sonificationPreferences,
        AiInsightPort aiInsight,
        org.ossproject.ai.NewsPort news,
        AiServiceProcess aiServiceProcess,
        VoiceInputPort voice,
        org.ossproject.voice.AudioCapturePort microphone,
        String marketDataSource,
        AnomalyAlertRepository anomalyAlerts,
        AutoCloseable persistence
) {
    private static final System.Logger LOGGER = System.getLogger(DesktopServices.class.getName());

    /** 테스트와 화면 갤러리에서 영속화 계층 없이 사용할 수 있는 호환 생성자. */
    public DesktopServices(
            TradingUseCase trading, MarketApplicationPort market, StockQueryPort stocks,
            CandleQueryPort candles, SpeechPort speech, SpeechQueue speechQueue, SoundPort sounds,
            SonificationPort sonification, SecretStore secrets, DesktopStateRepository stateRepository,
            AccessibilityPreferencesRepository accessibilityPreferences,
            SonificationPreferencesRepository sonificationPreferences, AiInsightPort aiInsight,
            org.ossproject.ai.NewsPort news, AiServiceProcess aiServiceProcess, String marketDataSource) {
        this(trading, market, stocks, candles, speech, speechQueue, sounds, sonification, secrets,
                stateRepository, accessibilityPreferences, sonificationPreferences, aiInsight, news,
                aiServiceProcess, VoiceInputPort.unavailable("음성 인식이 준비되지 않았습니다."),
                new MicrophoneCapture(),
                marketDataSource, unavailableAnomalyRepository(), () -> { });
    }

    public static DesktopServices createDefault() {
        SpeechPort speech = SpeechAdapterFactory.create();
        Path stateDirectory = stateDirectory();
        Path legacyState = stateDirectory.resolve("ui-state.properties");
        SecretStore secrets = createSecretStore(stateDirectory.resolve("secrets"));
        MarketDataSource source = createMarketDataSource(secrets);
        PersistenceServices persistence = createPersistence(stateDirectory, source.orders());
        AiService ai = createAiService(stateDirectory);
        MarketApplicationPort market = new MarketApplicationService(
                source.stocks(), source.candles(), source.orderBooks(), source.trades(),
                source.stream(), ForkJoinPool.commonPool(), ForkJoinPool.commonPool(),
                java.time.Clock.systemDefaultZone());

        return new DesktopServices(
                new TradingUseCase(persistence.orders(), source.account(),
                        new OrderGuard(OrderLimitPolicy.defaults()),
                        FeeSchedule.kiwoomMockDefaults()),
                market,
                source.stocks(),
                source.candles(),
                speech,
                new SpeechQueue(speech, defaultSpeechOptions()),
                new ToneSoundAdapter(),
                new PcmGraphSonificationAdapter(),
                secrets,
                new PropertiesDesktopStateRepository(legacyState),
                new PropertiesAccessibilityPreferencesRepository(
                        stateDirectory.resolve("accessibility.properties"), legacyState),
                new PropertiesSonificationPreferencesRepository(
                        stateDirectory.resolve("sonification.properties")),
                ai.port(), ai.news(), ai.process(), ai.voice(), ai.microphone(),
                source.description(), persistence.alerts(), persistence.closeable());
    }

    /**
     * 시세·계좌·주문 공급원과 사용자에게 보여 줄 설명.
     *
     * <p>실시간 스트림도 여기 함께 담는다. 조회는 증권사에서 받고 실시간은 가짜를 쓰면,
     * 화면에 시세가 멈춰 있어도 연결이 끊긴 것인지 장이 조용한 것인지 알 수 없다.
     */
    /**
     * AI 창구들과 그것을 띄운 프로세스. 프로세스는 앱이 꺼질 때 함께 내린다.
     *
     * <p>분석과 뉴스를 나눈 이유는 실패 범위가 다르기 때문이다. 뉴스는 남의 서버(RSS)를
     * 거치므로 예측·이상감지가 멀쩡해도 혼자 실패한다.
     */
    private record AiService(AiInsightPort port, org.ossproject.ai.NewsPort news,
                             AiServiceProcess process, VoiceInputPort voice,
                             org.ossproject.voice.AudioCapturePort microphone) {
    }

    /**
     * AI 분석을 준비한다.
     *
     * <p>저장소 안에서 {@code ai-service} 를 찾아 서버를 자식 프로세스로 띄운다. 사용자가
     * 터미널을 열어 직접 띄우게 하면 대부분은 AI 기능을 못 보고 지나간다.
     *
     * <p>파이썬이 없거나 의존 패키지가 설치되지 않았으면 띄우지 못한다. 그래도 앱은 그대로
     * 돌아간다. AI 는 부가 기능이고 시세와 주문은 이것 없이도 동작해야 한다. 어댑터는
     * 그대로 만들어 두고, 화면이 연결 상태를 물어 그 사실을 적는다.
     */
    private static AiService createAiService(Path stateDirectory) {
        int port = aiPort();
        URI baseUri = URI.create("http://127.0.0.1:" + port);
        AiInsightPort adapter = new HttpAiInsightAdapter(
                baseUri, java.time.Clock.systemDefaultZone());
        org.ossproject.ai.NewsPort news =
                new org.ossproject.ai.http.HttpNewsAdapter(baseUri);
        // 음성도 같은 서버를 쓴다. 마이크는 이 컴퓨터 것이다. 둘 중 하나만 없어도
        // 어댑터는 그대로 만들어 두고, 왜 못 쓰는지는 화면이 물어서 읽어 준다.
        MicrophoneCapture microphone = new MicrophoneCapture();
        VoiceInputPort voice = new HttpVoiceInputAdapter(baseUri, microphone);

        Optional<Path> directory = AiServiceProcess.locateServiceDirectory();
        if (directory.isEmpty()) {
            LOGGER.log(System.Logger.Level.INFO, "ai-service 를 찾지 못했습니다. AI 기능은 꺼집니다.");
            return new AiService(adapter, news, null, voice, microphone);
        }
        AiServiceProcess process = new AiServiceProcess(directory.get(), port,
                stateDirectory.resolve("ai-service.log"));
        if (!process.start()) {
            LOGGER.log(System.Logger.Level.INFO,
                    "AI 서버를 띄우지 못했습니다. ai-service 에서 pip install -r requirements.txt 를 실행해주세요.");
            return new AiService(adapter, news, null, voice, microphone);
        }
        return new AiService(adapter, news, process, voice, microphone);
    }

    /** 포트를 바꿔야 하는 환경을 위해 열어 둔다. */
    private static int aiPort() {
        String configured = System.getenv("OPENSTOCK_AI_PORT");
        if (configured == null || configured.isBlank()) {
            return AiServiceProcess.DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException ignored) {
            return AiServiceProcess.DEFAULT_PORT;
        }
    }

    private record MarketDataSource(
            StockQueryPort stocks,
            CandleQueryPort candles,
            AccountPort account,
            OrderLifecyclePort orders,
            OrderBookQueryPort orderBooks,
            TradeQueryPort trades,
            MarketDataStreamPort stream,
            String description) {
    }

    private record PersistenceServices(
            OrderLifecyclePort orders,
            AnomalyAlertRepository alerts,
            AutoCloseable closeable) {
    }

    private static PersistenceServices createPersistence(Path stateDirectory,
                                                         OrderLifecyclePort remoteOrders) {
        try {
            SqliteDatabase database = SqliteDatabase.open(stateDirectory.resolve("openstock.db"));
            SqliteOrderRepository orderRepository = new SqliteOrderRepository(database);
            return new PersistenceServices(
                    new PersistentOrderLifecyclePort(remoteOrders, orderRepository),
                    new SqliteAnomalyAlertRepository(database), database);
        } catch (RuntimeException unavailable) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "SQLite 영속화를 준비하지 못했습니다: {0}", unavailable.getMessage());
            return new PersistenceServices(remoteOrders, unavailableAnomalyRepository(), () -> { });
        }
    }

    private static AnomalyAlertRepository unavailableAnomalyRepository() {
        return new AnomalyAlertRepository() {
            @Override public void save(AnomalyAlert alert) { }
            @Override public java.util.List<AnomalyAlert> findRecent(int limit) {
                return java.util.List.of();
            }
            @Override public java.util.List<AnomalyAlert> findBySymbol(String symbol, int limit) {
                return java.util.List.of();
            }
            @Override public int deleteDetectedBefore(java.time.Instant cutoff) { return 0; }
        };
    }

    /**
     * 시세와 계좌, 주문 공급원을 고른다.
     *
     * <p>키움 자격증명이 환경변수에 있으면 모의투자 서버에 붙는다. 시세만 증권사에서 받고
     * 계좌와 주문은 앱 안에서 처리하면, 매수해도 잔고가 변하지 않아 어느 쪽이 실제인지
     * 구분할 수 없다. 세 가지를 같은 공급원으로 묶는다.
     *
     * <p>모의투자 서버로 보내는 주문은 실거래가 아니다. 증권사가 제공하는 연습 환경이며,
     * 실전 도메인으로는 {@code -Dossproject.trading.live=true} 없이 요청이 나가지 않는다.
     *
     * <p>자격증명이 없으면 값을 지어내지 않고 조회가 실패하도록 둔다. 연결에 실패해도 앱을
     * 띄우지 못하게 하지는 않는다. 접근성 기능은 시세 없이도 동작해야 한다.
     */
    private static MarketDataSource createMarketDataSource(SecretStore secrets) {
        String appKey = System.getenv("KIWOOM_APP_KEY");
        String appSecret = System.getenv("KIWOOM_APP_SECRET");
        char[] stored = null;
        if ((appKey == null || appKey.isBlank()) && (appSecret == null || appSecret.isBlank())
                && secrets.isAvailable()) {
            stored = secrets.load("kiwoom.mock.credentials").orElse(null);
            int separator = credentialSeparator(stored);
            if (separator > 0 && separator < stored.length - 1) {
                appKey = new String(stored, 0, separator);
                appSecret = new String(stored, separator + 1, stored.length - separator - 1);
            }
        }
        SecretBytes.wipe(stored);
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            return unavailable("키움 API 연결이 필요합니다."
                    + " 환경변수를 설정하거나 연결 화면에서 보호 저장한 뒤 다시 실행해주세요.");
        }
        try {
            KiwoomMarketAdapters kiwoom = KiwoomMarketAdapters.mockTrading(appKey, appSecret);
            return new MarketDataSource(kiwoom.stocks(), kiwoom.candles(),
                    kiwoom.account(), kiwoom.orders(), kiwoom.orderBooks(), kiwoom.trades(),
                    kiwoom.stream(), "키움 모의투자");
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "키움 연결을 준비하지 못했습니다.", failure);
            return unavailable("키움 연결을 준비하지 못했습니다. 자격증명과 네트워크를 확인해주세요.");
        }
    }

    /** 입력한 키로 토큰과 모의계좌 조회까지 수행해 연결 가능 여부를 확인한다. */
    public static String verifyMockCredentials(String appKey, char[] appSecret) {
        if (appSecret == null || appSecret.length == 0) {
            throw new IllegalArgumentException("App Secret은 필수입니다.");
        }
        KiwoomMarketAdapters adapters = KiwoomMarketAdapters.mockTrading(appKey, new String(appSecret));
        try {
            return "계좌 " + adapters.account().getAccount().maskedAccountNo();
        } finally {
            adapters.stream().close();
        }
    }

    private static int credentialSeparator(char[] credentials) {
        if (credentials == null) return -1;
        for (int index = 0; index < credentials.length; index++) {
            if (credentials[index] == '\n') return index;
        }
        return -1;
    }

    private static MarketDataSource unavailable(String reason) {
        UnavailableMarketData none = new UnavailableMarketData(reason);
        return new MarketDataSource(none, none, none, none, none, none, none, "미연결 · " + reason);
    }

    private static SpeechOptions defaultSpeechOptions() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win")
                ? SpeechOptions.DEFAULT.withVoiceName("Microsoft Heami Desktop")
                : SpeechOptions.DEFAULT;
    }

    private static Path stateDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        return localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".openstock-access")
                : Path.of(localAppData, "OpenStockAccess");
    }

    private static SecretStore createSecretStore(Path directory) {
        try {
            return SecretStoreFactory.create(directory);
        } catch (SecretStoreException unavailable) {
            return new UnavailableSecretStore(unavailable.getMessage());
        }
    }
}
