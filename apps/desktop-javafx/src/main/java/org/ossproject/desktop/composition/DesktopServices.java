package org.ossproject.desktop.composition;

import org.ossproject.accessibility.infrastructure.sound.ToneSoundAdapter;
import org.ossproject.accessibility.infrastructure.speech.SpeechAdapterFactory;
import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechQueue;
import org.ossproject.accessibility.port.SoundPort;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.application.policy.OrderGuard;
import org.ossproject.application.policy.OrderLimitPolicy;
import org.ossproject.application.port.CandleQueryPort;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.desktop.persistence.AccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.DesktopStateRepository;
import org.ossproject.desktop.persistence.PropertiesAccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.PropertiesDesktopStateRepository;
import org.ossproject.desktop.persistence.PropertiesSonificationPreferencesRepository;
import org.ossproject.desktop.persistence.SonificationPreferencesRepository;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.kiwoom.KiwoomMarketAdapters;
import org.ossproject.mocktrading.DemoTradingAccounts;
import org.ossproject.mocktrading.FillMode;
import org.ossproject.mocktrading.MockTradingEngine;
import org.ossproject.sonification.javasound.PcmGraphSonificationAdapter;
import org.ossproject.sonification.port.SonificationPort;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.SecretStoreException;
import org.ossproject.secret.windows.SecretStoreFactory;

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
        String marketDataSource
) {
    private static final System.Logger LOGGER = System.getLogger(DesktopServices.class.getName());

    public static DesktopServices createDefault() {
        MockTradingEngine tradingEngine = new MockTradingEngine(
                DemoTradingAccounts.koreanStocks(), FillMode.MANUAL);
        SpeechPort speech = SpeechAdapterFactory.create();
        Path stateDirectory = stateDirectory();
        Path legacyState = stateDirectory.resolve("ui-state.properties");
        SecretStore secrets = createSecretStore(stateDirectory.resolve("secrets"));
        MarketDataSource source = createMarketDataSource();
        MarketApplicationPort market = new MarketApplicationService(
                source.stocks(), source.candles(),
                new FakeMarketDataStreamAdapter(), ForkJoinPool.commonPool());

        return new DesktopServices(
                new TradingUseCase(tradingEngine, tradingEngine,
                        new OrderGuard(OrderLimitPolicy.defaults())),
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
                source.description());
    }

    /** 시세 공급원과 사용자에게 보여 줄 설명. */
    private record MarketDataSource(StockQueryPort stocks, CandleQueryPort candles, String description) {
    }

    /**
     * 시세 공급원을 고른다.
     *
     * <p>키움 자격증명이 환경변수에 있으면 모의투자 서버에 붙는다. 없으면 가짜 시세를 대신
     * 넣지 않고 조회가 실패하도록 둔다. 가짜 값은 실제 시세와 똑같이 생겨서, 화면을 볼 수
     * 없는 사용자는 지금 듣고 있는 가격이 실제 시장 값인지 구분할 수 없다.
     *
     * <p>연결에 실패해도 앱을 띄우지 못하게 하지는 않는다. 시세를 못 받는 것과 앱을 아예
     * 쓰지 못하는 것은 다르고, 접근성 기능은 시세 없이도 동작해야 한다.
     */
    private static MarketDataSource createMarketDataSource() {
        String appKey = System.getenv("KIWOOM_APP_KEY");
        String appSecret = System.getenv("KIWOOM_APP_SECRET");
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            return unavailable("키움 API 연결이 필요합니다."
                    + " 환경변수 KIWOOM_APP_KEY 와 KIWOOM_APP_SECRET 을 설정한 뒤 다시 실행해주세요.");
        }
        try {
            KiwoomMarketAdapters kiwoom = KiwoomMarketAdapters.mockTrading(appKey, appSecret);
            return new MarketDataSource(kiwoom.stocks(), kiwoom.candles(), "키움 모의투자");
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "키움 시세 연결을 준비하지 못했습니다.", failure);
            return unavailable("키움 시세 연결을 준비하지 못했습니다. 자격증명과 네트워크를 확인해주세요.");
        }
    }

    private static MarketDataSource unavailable(String reason) {
        UnavailableMarketData none = new UnavailableMarketData(reason);
        return new MarketDataSource(none, none, "미연결 · " + reason);
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
