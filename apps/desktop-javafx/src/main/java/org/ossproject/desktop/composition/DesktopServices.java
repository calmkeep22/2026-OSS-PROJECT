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
import org.ossproject.application.port.OrderLifecyclePort;
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
import org.ossproject.kiwoom.KiwoomMarketAdapters;
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
        SpeechPort speech = SpeechAdapterFactory.create();
        Path stateDirectory = stateDirectory();
        Path legacyState = stateDirectory.resolve("ui-state.properties");
        SecretStore secrets = createSecretStore(stateDirectory.resolve("secrets"));
        MarketDataSource source = createMarketDataSource();
        MarketApplicationPort market = new MarketApplicationService(
                source.stocks(), source.candles(), source.stream(), ForkJoinPool.commonPool());

        return new DesktopServices(
                new TradingUseCase(source.orders(), source.account(),
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

    /**
     * 시세·계좌·주문 공급원과 사용자에게 보여 줄 설명.
     *
     * <p>실시간 스트림도 여기 함께 담는다. 조회는 증권사에서 받고 실시간은 가짜를 쓰면,
     * 화면에 시세가 멈춰 있어도 연결이 끊긴 것인지 장이 조용한 것인지 알 수 없다.
     */
    private record MarketDataSource(
            StockQueryPort stocks,
            CandleQueryPort candles,
            AccountPort account,
            OrderLifecyclePort orders,
            MarketDataStreamPort stream,
            String description) {
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
    private static MarketDataSource createMarketDataSource() {
        String appKey = System.getenv("KIWOOM_APP_KEY");
        String appSecret = System.getenv("KIWOOM_APP_SECRET");
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            return unavailable("키움 API 연결이 필요합니다."
                    + " 환경변수 KIWOOM_APP_KEY 와 KIWOOM_APP_SECRET 을 설정한 뒤 다시 실행해주세요.");
        }
        try {
            KiwoomMarketAdapters kiwoom = KiwoomMarketAdapters.mockTrading(appKey, appSecret);
            return new MarketDataSource(kiwoom.stocks(), kiwoom.candles(),
                    kiwoom.account(), kiwoom.orders(), kiwoom.stream(), "키움 모의투자");
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "키움 연결을 준비하지 못했습니다.", failure);
            return unavailable("키움 연결을 준비하지 못했습니다. 자격증명과 네트워크를 확인해주세요.");
        }
    }

    private static MarketDataSource unavailable(String reason) {
        UnavailableMarketData none = new UnavailableMarketData(reason);
        return new MarketDataSource(none, none, none, none, none, "미연결 · " + reason);
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
