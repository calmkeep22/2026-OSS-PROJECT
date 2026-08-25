package org.ossproject.desktop;

import javafx.scene.Parent;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.accessibility.notification.SoundCue;
import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechQueue;
import org.ossproject.accessibility.port.SoundPort;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AiInsightPort;
import org.ossproject.ai.AnomalySignal;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.ai.Confidence;
import org.ossproject.ai.NewsArticle;
import org.ossproject.ai.NewsDigest;
import org.ossproject.ai.NewsPort;
import org.ossproject.ai.SimilarOutlook;
import org.ossproject.ai.SimilarStock;
import org.ossproject.application.policy.OrderGuard;
import org.ossproject.application.policy.OrderLimitPolicy;
import org.ossproject.application.port.TradeQueryPort;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.application.usecase.TradingUseCase;
import org.ossproject.desktop.composition.DesktopServices;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.persistence.PropertiesAccessibilityPreferencesRepository;
import org.ossproject.desktop.persistence.PropertiesDesktopStateRepository;
import org.ossproject.desktop.persistence.PropertiesSonificationPreferencesRepository;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.desktop.testsupport.ScreenshotProbe;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeOrderBookFeed;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.order.FeeSchedule;
import org.ossproject.mocktrading.DemoTradingAccounts;
import org.ossproject.mocktrading.FillMode;
import org.ossproject.mocktrading.MockTradingEngine;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStore;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.port.SonificationOverflowPolicy;
import org.ossproject.sonification.port.SonificationPort;

import java.io.File;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 앱을 통째로 세워 창을 그대로 그림으로 남긴다.
 *
 * <p>화면 조각만 따로 그리면 상단 막대와 왼쪽 이동 막대가 빠진 그림이 나온다. 사용자가
 * 보는 것은 조각이 아니라 창 하나이므로, 조립 루트에 가짜 어댑터를 물려 실제 창을 띄우고
 * 화면을 하나씩 옮겨 가며 찍는다.
 *
 * <p>사이드바에 나오는 화면과 거기서 열리는 종목 상세만 찍는다. 숨겨 둔 화면(시장·랭킹·
 * 조건검색·미국주식)은 아직 연동되지 않아 사용자가 갈 수 없으므로 그림으로 남기지 않는다.
 */
@ExtendWith(JavaFxToolkit.class)
class AppGallery {

    private static final Path OUT = Path.of("build", "screens");
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 900;

    /** 사용자가 실제로 오갈 수 있는 화면. */
    private static final List<Screen> IN_USE = List.of(
            Screen.DASHBOARD, Screen.CONNECTION, Screen.SEARCH, Screen.STOCK_DETAIL,
            Screen.WATCHLIST, Screen.TRADING, Screen.ACCOUNT, Screen.SIMILAR,
            Screen.NEWS, Screen.ANOMALY, Screen.NOTIFICATIONS, Screen.RADIO, Screen.SETTINGS);

    @Test
    @DisplayName("쓰는 화면을 창째로 그림으로 남긴다")
    void captureEveryScreenInUse() throws Exception {
        if (Files.exists(OUT)) {
            try (var stream = Files.list(OUT)) {
                for (Path stale : stream.toList()) Files.deleteIfExists(stale);
            }
        }
        Files.createDirectories(OUT);

        DesktopApplication[] app = new DesktopApplication[1];
        Stage[] stage = new Stage[1];
        JavaFxToolkit.onFxThread(() -> {
            app[0] = new DesktopApplication(services());
            stage[0] = new Stage();
            startWithoutShowing(app[0], stage[0]);
        });
        settle();

        int index = 1;
        for (Screen screen : IN_USE) {
            String name = String.format("%02d-%s", index++, slug(screen));
            JavaFxToolkit.onFxThread(() -> navigate(app[0], screen));
            settle();
            JavaFxToolkit.onFxThread(() -> {
                call(app[0], "showSidebar");
                shoot(name, stage[0]);
            });
        }

        List<Path> shots;
        try (var stream = Files.list(OUT)) {
            shots = stream.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .sorted().toList();
        }
        shots.forEach(path -> System.out.println("남김 " + path.getFileName()));
        assertEquals(IN_USE.size(), shots.size(), "찍힌 화면 수");
        for (Path shot : shots) {
            assertTrue(Files.size(shot) > 3000, shot + " 이 비어 있습니다.");
        }
    }

    /** 큐에 쌓인 뒷일이 끝나도록 화면 스레드를 몇 번 비운다. */
    private static void settle() throws InterruptedException {
        for (int round = 0; round < 4; round++) {
            JavaFxToolkit.onFxThread(() -> { });
            Thread.sleep(80);
        }
    }

    private static void shoot(String name, Stage stage) {
        Parent root = stage.getScene().getRoot();
        // 창을 띄우지 않으므로 장면이 크기를 정해 주지 않는다. 찍기 직전에 직접 잡는다.
        root.resize(WIDTH, HEIGHT);
        root.applyCss();
        root.layout();
        WritableImage image = root.snapshot(null, null);
        try {
            ScreenshotProbe.write(image, new File(OUT.toFile(), name + ".png"));
        } catch (Exception failure) {
            throw new IllegalStateException(name + " 저장 실패", failure);
        }
    }

    private static void navigate(DesktopApplication app, Screen screen) {
        try {
            Method navigate = DesktopApplication.class.getDeclaredMethod("navigate", Screen.class);
            navigate.setAccessible(true);
            navigate.invoke(app, screen);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(screen + " 로 이동하지 못했습니다.", failure);
        }
    }

    /**
     * 이동 막대는 왼쪽 가장자리에 마우스를 대야 나오는 겹침 층이다. 마우스가 없는
     * 촬영에서는 직접 펼쳐 준다. 접어 둔 그림만 남기면 이동 수단이 안 보인다.
     */
    private static void call(DesktopApplication app, String methodName) {
        try {
            Method method = DesktopApplication.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(app);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(methodName + " 를 부르지 못했습니다.", failure);
        }
    }

    /**
     * 앱을 세우되 창은 뜨지 않아도 넘어간다.
     *
     * <p>테스트에 딸려 오는 Monocle 은 JavaFX 12 용이라 17 에서 창을 띄우려 하면
     * {@code AbstractMethodError} 가 난다. {@code start} 의 마지막 줄이 창 띄우기이고
     * 장면은 그 직전에 이미 붙으므로, 여기까지 왔으면 화면은 다 세워진 것이다.
     * 그림을 뽑는 데 창은 필요 없다.
     */
    private static void startWithoutShowing(DesktopApplication app, Stage stage) {
        try {
            app.start(stage);
        } catch (AbstractMethodError headless) {
            if (stage.getScene() == null) throw headless;
        }
    }

    private static String slug(Screen screen) {
        return screen.label().replace(" · ", "-").replace(" ", "");
    }

    // ---- 가짜 조립 루트 -------------------------------------------------

    private static DesktopServices services() {
        Path temporary;
        try {
            temporary = Files.createTempDirectory("openstock-gallery");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        FakeStockQueryAdapter stocks = new FakeStockQueryAdapter();
        FakeCandleQueryAdapter candles = new FakeCandleQueryAdapter();
        FakeMarketDataStreamAdapter stream = new FakeMarketDataStreamAdapter();
        FakeOrderBookFeed orderBooks = new FakeOrderBookFeed("005930",
                new BigDecimal("70000"), new BigDecimal("100"), 7L);
        MockTradingEngine engine = new MockTradingEngine(
                DemoTradingAccounts.koreanStocks(), FillMode.IMMEDIATE);
        TradeQueryPort trades = symbol -> List.of();

        Path legacy = temporary.resolve("ui-state.properties");
        return new DesktopServices(
                new TradingUseCase(engine, engine, new OrderGuard(OrderLimitPolicy.defaults()),
                        FeeSchedule.kiwoomMockDefaults()),
                new MarketApplicationService(stocks, candles, orderBooks, trades, stream,
                        Runnable::run, Runnable::run, Clock.systemDefaultZone()),
                stocks, candles,
                new SilentSpeech(),
                new SpeechQueue(new SilentSpeech(), SpeechOptions.DEFAULT),
                new SilentSound(),
                new SilentSonification(),
                new MemorySecrets(),
                new PropertiesDesktopStateRepository(legacy),
                new PropertiesAccessibilityPreferencesRepository(
                        temporary.resolve("accessibility.properties"), legacy),
                new PropertiesSonificationPreferencesRepository(
                        temporary.resolve("sonification.properties")),
                new SampleInsights(), new SampleNews(), null,
                "예시 데이터 (그림 촬영용)");
    }

    private static final class SilentSpeech implements SpeechPort {
        @Override public void speak(String text) { }
        @Override public void stop() { }
    }

    private static final class SilentSound implements SoundPort {
        @Override public void play(SoundCue cue) { }
        @Override public void stop() { }
        @Override public void setVolume(double volume) { }
    }

    private static final class SilentSonification implements SonificationPort {
        @Override public void play(GraphAudioFrame frame) { }
        @Override public void stop() { }
        @Override public void setVolume(double volume) { }
        @Override public SonificationOverflowPolicy overflowPolicy() {
            return SonificationOverflowPolicy.DROP_OLDEST;
        }
        @Override public void close() { }
    }

    private static final class MemorySecrets implements SecretStore {
        private final Map<String, char[]> held = new LinkedHashMap<>();
        @Override public void store(String alias, char[] secret) { held.put(alias, secret.clone()); }
        @Override public Optional<char[]> load(String alias) {
            return Optional.ofNullable(held.get(alias)).map(char[]::clone);
        }
        @Override public void delete(String alias) { held.remove(alias); }
        @Override public boolean contains(String alias) { return held.containsKey(alias); }
        @Override public Set<String> aliases() { return Set.copyOf(held.keySet()); }
        @Override public SecretProtectionLevel protectionLevel() {
            return SecretProtectionLevel.OS_USER_PROTECTED;
        }
        @Override public String description() { return "그림 촬영용 임시 보관"; }
        @Override public void close() { held.clear(); }
    }

    private static final class SampleInsights implements AiInsightPort {
        @Override public AiInsight brief(SecurityId security, List<Candle> bars, boolean withSimilar) {
            return insight(security.symbol(), name(security.symbol()));
        }
        @Override public boolean available() { return true; }
        @Override public String unavailableReason() { return ""; }
    }

    private static final class SampleNews implements NewsPort {
        @Override public NewsDigest news(SecurityId security) {
            return digest(security.symbol(), name(security.symbol()));
        }
        @Override public ChatAnswer ask(SecurityId security, String question, AiInsight context) {
            return new ChatAnswer(
                    "단정할 수 없습니다. 뉴스가 주가를 어떻게 움직이는지는 저희가 재지 않은 것입니다.",
                    List.of("뉴스 감성 분석"), false, List.of("핵심 수치 알려줘"));
        }
        @Override public void track(List<SecurityId> securities) { }
    }

    private static final Map<String, String> NAMES = new HashMap<>(Map.of(
            "005930", "삼성전자", "000660", "SK하이닉스", "035720", "카카오",
            "035420", "NAVER", "000810", "삼성화재"));

    private static String name(String symbol) {
        return NAMES.getOrDefault(symbol, symbol);
    }

    private static AiInsight insight(String symbol, String label) {
        AnomalySignal signal = new AnomalySignal(false, "정상", "상승",
                LocalDate.of(2026, 8, 21), new BigDecimal("1.38"),
                "상위 12퍼센트", "변동이 큰 편입니다. 분할 매수를 권합니다.");
        return new AiInsight(symbol, label,
                label + "는 2026-08-21 기준 평소 범위 안에서 움직였습니다. 등락률 1.38퍼센트입니다.",
                Confidence.HIGH, true, Optional.empty(), Optional.empty(),
                Optional.of(signal),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("91"),
                                Optional.of(new BigDecimal("21")),
                                "유사도 91퍼센트. 완만한 상승 후 거래량 급증, 같은 패턴."),
                        new SimilarStock("035420", "NAVER", new BigDecimal("87"),
                                Optional.of(new BigDecimal("64")), "단기 급등 후 조정, 변동성 흐름 유사.")),
                Optional.of(new SimilarOutlook(5, 3, 2, "표본이 적고 미래를 보장하지 않습니다.",
                        "유사도는 형태가 닮았다는 뜻이며 미래 수익률을 의미하지 않습니다.")),
                Map.of());
    }

    private static NewsDigest digest(String symbol, String label) {
        List<NewsArticle> articles = new ArrayList<>();
        articles.add(new NewsArticle(label + ", 신규 시설 투자 계획 공시", "경제 신문",
                Instant.parse("2026-08-22T02:02:00Z"), "https://example.test/1",
                Optional.of("positive")));
        articles.add(new NewsArticle(label + " 업종 거래량 확대", "거래소 공시",
                Instant.parse("2026-08-22T01:35:00Z"), "https://example.test/2",
                Optional.of("neutral")));
        return new NewsDigest(symbol, label, Optional.of(9.2), "중립", 21, 9, 14,
                List.of(label + " 주가 회복 후 숨고르기. 긍정적 내용입니다.",
                        "[특징주] " + label + ", 배당 확대 기대감에 강세.",
                        label + " 등 업종 실적 개선 전망."),
                Optional.of("오늘 시황 보도입니다. 지수가 강보합입니다."),
                articles,
                label + " 뉴스 브리핑입니다. 뉴스 감성 지수는 여론의 방향을 요약한 것이며 주가 예측이 아닙니다.");
    }
}
