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
        SonificationPreferencesRepository sonificationPreferences
) {
    public static DesktopServices createDefault() {
        MockTradingEngine tradingEngine = new MockTradingEngine(
                DemoTradingAccounts.koreanStocks(), FillMode.MANUAL);
        SpeechPort speech = SpeechAdapterFactory.create();
        Path stateDirectory = stateDirectory();
        Path legacyState = stateDirectory.resolve("ui-state.properties");
        SecretStore secrets = createSecretStore(stateDirectory.resolve("secrets"));
        StockQueryPort stocks = new FakeStockQueryAdapter();
        CandleQueryPort candles = new FakeCandleQueryAdapter();
        MarketApplicationPort market = new MarketApplicationService(
                stocks, candles, new FakeMarketDataStreamAdapter(), ForkJoinPool.commonPool());

        return new DesktopServices(
                new TradingUseCase(tradingEngine, tradingEngine,
                        new OrderGuard(OrderLimitPolicy.defaults())),
                market,
                stocks,
                candles,
                speech,
                new SpeechQueue(speech, defaultSpeechOptions()),
                new ToneSoundAdapter(),
                new PcmGraphSonificationAdapter(),
                secrets,
                new PropertiesDesktopStateRepository(legacyState),
                new PropertiesAccessibilityPreferencesRepository(
                        stateDirectory.resolve("accessibility.properties"), legacyState),
                new PropertiesSonificationPreferencesRepository(
                        stateDirectory.resolve("sonification.properties")));
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
