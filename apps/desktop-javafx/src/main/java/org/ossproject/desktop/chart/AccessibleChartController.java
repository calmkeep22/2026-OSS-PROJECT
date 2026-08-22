package org.ossproject.desktop.chart;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.ossproject.application.port.ConnectionState;
import org.ossproject.application.port.EventSubscription;
import org.ossproject.application.port.MarketApplicationListener;
import org.ossproject.application.port.MarketApplicationPort;
import org.ossproject.desktop.state.SonificationPreferences;
import org.ossproject.finance.model.Candle;
import org.ossproject.finance.model.Quote;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.StockDetail;
import org.ossproject.sonification.analysis.GraphAnalyzer;
import org.ossproject.sonification.playback.GraphPlaybackController;
import org.ossproject.sonification.playback.GraphPlaybackListener;
import org.ossproject.sonification.playback.GraphPlaybackPlan;
import org.ossproject.sonification.playback.GraphPlaybackPlanner;
import org.ossproject.sonification.playback.GraphPlaybackState;
import org.ossproject.sonification.playback.GraphSonificationListener;
import org.ossproject.sonification.analysis.LargestTriangleThreeBucketsReducer;
import org.ossproject.sonification.playback.StreamingGraphSonifier;
import org.ossproject.sonification.timing.TimestampProportionalTimeMapping;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.model.GraphScaleMode;
import org.ossproject.sonification.model.GraphSonificationConfig;
import org.ossproject.sonification.model.GraphSummary;
import org.ossproject.sonification.model.GraphValueScale;
import org.ossproject.sonification.model.TimeSeriesSample;
import org.ossproject.sonification.port.SonificationPort;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns accessible-chart state and bridges application market events to the reusable audio core. */
public final class AccessibleChartController implements AutoCloseable {
    private static final int MAXIMUM_PLAYBACK_POINTS = 48;
    private static final Duration TARGET_PLAYBACK_DURATION = Duration.ofSeconds(12);
    private static final double SPEECH_DUCKING_RATIO = 0.15;

    private final SecurityId security;
    private final String streamKey;
    private final String seriesDescription;
    private final MarketApplicationPort market;
    private final SonificationPort audio;
    private final StreamingGraphSonifier sonifier;
    private final GraphPlaybackController playback;
    private final GraphPlaybackPlanner planner = new GraphPlaybackPlanner(
            new LargestTriangleThreeBucketsReducer(), new TimestampProportionalTimeMapping());
    private final ChartAnnouncementSink announcements;
    private final Consumer<String> applicationStatus;
    private final Executor uiExecutor;
    private final ChartTextFormatter text = new ChartTextFormatter(ZoneId.systemDefault());
    private final ObservableList<String> pointLabels = FXCollections.observableArrayList();
    private final SimpleStringProperty playbackStatus = new SimpleStringProperty("차트 데이터를 준비하는 중입니다.");
    private final SimpleStringProperty liveStatus = new SimpleStringProperty("중지됨");
    private final SimpleStringProperty scaleDescription = new SimpleStringProperty();
    private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object liveLock = new Object();

    private final StockDetail stock;
    private final List<TimeSeriesSample> samples;
    private final GraphSummary summary;
    private GraphScaleMode scaleMode = GraphScaleMode.AUTOMATIC;
    private GraphValueScale activeScale;
    private double percentRange = 5.0;
    private volatile double volume = 0.8;
    private volatile boolean speechActive;
    private Consumer<SonificationPreferences> preferencesListener = ignored -> { };
    private EventSubscription liveSubscription;
    private boolean liveRunning;
    private long liveGeneration;
    private Instant lastLiveTimestamp;
    private ConnectionState liveConnectionState;

    public AccessibleChartController(
            SecurityId security,
            StockDetail stock,
            List<Candle> candles,
            String seriesDescription,
            MarketApplicationPort market,
            SonificationPort audio,
            ChartAnnouncementSink announcements,
            Consumer<String> applicationStatus
    ) {
        this(security, stock, candles, seriesDescription, market, audio, announcements,
                applicationStatus, AccessibleChartController::runOnFxThread);
    }

    AccessibleChartController(
            SecurityId security,
            StockDetail stock,
            List<Candle> candles,
            String seriesDescription,
            MarketApplicationPort market,
            SonificationPort audio,
            ChartAnnouncementSink announcements,
            Consumer<String> applicationStatus,
            Executor uiExecutor
    ) {
        this.security = Objects.requireNonNull(security, "security");
        this.streamKey = security.exchange().name() + ":" + security.symbol();
        this.stock = Objects.requireNonNull(stock, "stock");
        if (!security.symbol().equalsIgnoreCase(stock.symbol())) {
            throw new IllegalArgumentException("종목 상세와 청각 차트 식별자가 일치해야 합니다.");
        }
        if (seriesDescription == null || seriesDescription.isBlank()) {
            throw new IllegalArgumentException("차트 기간 설명은 필수입니다.");
        }
        this.seriesDescription = seriesDescription.strip();
        this.market = Objects.requireNonNull(market, "market");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.announcements = Objects.requireNonNull(announcements, "announcements");
        this.applicationStatus = Objects.requireNonNull(applicationStatus, "applicationStatus");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.sonifier = new StreamingGraphSonifier(audio,
                new GraphSonificationConfig(220, 440, 880, 5.0, Duration.ofSeconds(1)));
        this.playback = new GraphPlaybackController(sonifier);
        this.samples = toSamples(streamKey, candles);
        if (samples.size() < 2) {
            throw new IllegalStateException("청각 차트에는 가격 지점이 두 개 이상 필요합니다.");
        }
        this.summary = GraphAnalyzer.summarize(samples);
        configureListeners();
        refreshPointLabels();
        reloadScale();
        applyVolume();
    }

    public SecurityId security() { return security; }
    public StockDetail stock() { return stock; }
    public String seriesDescription() { return seriesDescription; }
    public List<TimeSeriesSample> samples() { return samples; }
    public ObservableList<String> pointLabels() { return pointLabels; }
    public ReadOnlyStringProperty playbackStatusProperty() { return playbackStatus; }
    public ReadOnlyStringProperty liveStatusProperty() { return liveStatus; }
    public ReadOnlyStringProperty scaleDescriptionProperty() { return scaleDescription; }
    public ReadOnlyIntegerProperty selectedIndexProperty() { return selectedIndex; }
    public GraphPlaybackState playbackState() { return playback.state(); }
    public GraphScaleMode scaleMode() { return scaleMode; }
    public double percentRange() { return percentRange; }
    public double speed() { return playback.speed(); }
    public double volume() { return volume; }
    public boolean isLiveRunning() {
        synchronized (liveLock) { return liveRunning; }
    }

    public SonificationPreferences preferences() {
        return new SonificationPreferences(scaleMode, percentRange, playback.speed(), volume);
    }

    public String summaryText() { return text.summary(stock.name(), summary); }

    public void applyPreferences(SonificationPreferences preferences) {
        SonificationPreferences checked = Objects.requireNonNull(preferences, "preferences");
        scaleMode = checked.scaleMode();
        percentRange = checked.percentRange();
        volume = checked.volume();
        playback.setSpeed(checked.playbackSpeed());
        reloadScale();
        applyVolume();
    }

    public void setPreferencesListener(Consumer<SonificationPreferences> listener) {
        preferencesListener = Objects.requireNonNull(listener, "listener");
    }

    public void play() {
        stopLiveInternal(null, false);
        playback.play();
        applicationStatus.accept("청각 차트 전체 그래프를 재생합니다.");
    }

    public void pause() { playback.pause(); }
    public void stop() { playback.stop(); }

    public void replay() {
        stopLiveInternal(null, false);
        playback.replay();
        applicationStatus.accept("청각 차트를 처음부터 다시 재생합니다.");
    }

    public void seek(int index) {
        stopLiveInternal(null, false);
        playback.seek(index);
    }

    public void setSpeed(double speed) {
        playback.setSpeed(speed);
        notifyPreferencesChanged();
    }

    public void setScaleMode(GraphScaleMode mode) {
        GraphScaleMode checked = Objects.requireNonNull(mode, "mode");
        if (checked == scaleMode) return;
        scaleMode = checked;
        reloadScale();
        notifyPreferencesChanged();
    }

    public void setPercentRange(double percentRange) {
        if (!Double.isFinite(percentRange) || percentRange <= 0 || percentRange >= 100) {
            throw new IllegalArgumentException("percentRange must be between zero and one hundred");
        }
        this.percentRange = percentRange;
        if (scaleMode == GraphScaleMode.PERCENT_FROM_REFERENCE) reloadScale();
        else refreshScaleDescription();
        notifyPreferencesChanged();
    }

    public void setVolume(double volume) {
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) {
            throw new IllegalArgumentException("volume must be between zero and one");
        }
        this.volume = volume;
        applyVolume();
        notifyPreferencesChanged();
    }

    /** Lowers graph audio while TTS is speaking, then restores the user's configured volume. */
    public void setSpeechActive(boolean speechActive) {
        this.speechActive = speechActive;
        applyVolume();
    }

    public void announceSummary() {
        String description = summaryText();
        applicationStatus.accept(description);
        announcements.announce(description, "accessible-chart-summary");
    }

    public void announcePoint(int index) {
        if (index < 0 || index >= samples.size()) return;
        playback.pause();
        sonifier.stop();
        String description = text.exactPoint(samples.get(index), summary.first().value());
        applicationStatus.accept(description);
        announcements.announce(description, "accessible-chart-point");
    }

    /** Starts a real market subscription. Repeated starts retain only one subscription. */
    public void startLive() {
        long generation;
        synchronized (liveLock) {
            ensureOpen();
            if (liveRunning) return;
            liveRunning = true;
            generation = ++liveGeneration;
            lastLiveTimestamp = null;
            liveConnectionState = null;
        }

        try {
            playback.pause();
            sonifier.stop();
            TimeSeriesSample anchor = samples.get(samples.size() - 1);
            sonifier.startAt(streamKey, activeScale, anchor.value());
            EventSubscription subscription = market.monitor(security, new MarketApplicationListener() {
                @Override public void onQuote(Quote quote) {
                    acceptLiveQuote(generation, quote);
                }

                @Override public void onConnectionChanged(ConnectionState state, String safeDetail) {
                    updateConnectionState(generation, state, safeDetail);
                }
            });

            boolean retained;
            ConnectionState connectionState;
            synchronized (liveLock) {
                retained = !closed.get() && liveRunning && generation == liveGeneration;
                if (retained) liveSubscription = subscription;
                connectionState = liveConnectionState;
            }
            if (!retained) closeSubscription(subscription, false);
            if (retained) runOnUi(() -> {
                if (!isLiveGeneration(generation)) return;
                if (connectionState == null) {
                    liveStatus.set("연결 상태 확인 중 · " + stock.name()
                            + " 실시간 시세를 기다리고 있습니다.");
                }
                applicationStatus.accept("실시간 청각 차트 모니터링을 시작했습니다.");
            });
        } catch (RuntimeException failure) {
            handleLiveFailure(generation, "시작 실패", failure);
        }
    }

    public void stopLive() {
        boolean wasRunning = stopLiveInternal(null, true);
        runOnUi(() -> {
            liveStatus.set("중지됨");
            if (wasRunning) applicationStatus.accept("실시간 청각 차트 모니터링을 중지했습니다.");
        });
    }

    private void acceptLiveQuote(long generation, Quote quote) {
        if (quote == null || !security.symbol().equalsIgnoreCase(quote.symbol())) return;
        try {
            synchronized (liveLock) {
                if (!isLiveGenerationLocked(generation)) return;
                if (lastLiveTimestamp != null && quote.timestamp().isBefore(lastLiveTimestamp)) return;
                lastLiveTimestamp = quote.timestamp();
                sonifier.accept(new TimeSeriesSample(
                        streamKey, quote.price().doubleValue(), quote.timestamp()));
            }
        } catch (RuntimeException failure) {
            handleLiveFailure(generation, "데이터 재생 실패", failure);
        }
    }

    private void updateConnectionState(long generation, ConnectionState state, String safeDetail) {
        if (state == null) return;
        synchronized (liveLock) {
            if (!isLiveGenerationLocked(generation)) return;
            liveConnectionState = state;
        }
        if (!state.isUsable()) {
            try {
                audio.stop();
            } catch (RuntimeException failure) {
                reportAudioFailure(failure);
            }
        }
        String detail = safeDetail == null || safeDetail.isBlank() ? "" : " · " + safeDetail.strip();
        runOnUi(() -> {
            if (!isLiveGeneration(generation)) return;
            String description = switch (state) {
                case CONNECTED -> "연결됨 · 실시간 시세를 기다리는 중입니다.";
                case CONNECTING -> "연결 중" + detail;
                case RECONNECTING -> "일시 중지 · 재연결 중" + detail;
                case DISCONNECTED -> "일시 중지 · 연결 끊김" + detail;
                case FAILED -> "일시 중지 · 연결 실패" + detail;
            };
            liveStatus.set(description);
            applicationStatus.accept("실시간 청각 차트: " + description);
        });
    }

    private void configureListeners() {
        playback.addListener(new GraphPlaybackListener() {
            @Override public void onStateChanged(GraphPlaybackState state) {
                runOnUi(() -> playbackStatus.set(switch (state) {
                    case EMPTY -> "차트 데이터가 없습니다.";
                    case READY -> "재생 준비됨 · Space 또는 전체 그래프 재생 버튼을 누르세요.";
                    case PLAYING -> "실제 날짜 간격을 반영해 전체 그래프를 재생하고 있습니다.";
                    case PAUSED -> "일시정지됨 · 좌우 방향키로 가격 지점을 탐색할 수 있습니다.";
                    case COMPLETED -> "그래프 끝까지 재생했습니다. R 키로 다시 들을 수 있습니다.";
                }));
            }

            @Override public void onPointChanged(
                    int index, int total, TimeSeriesSample sample, GraphAudioFrame frame
            ) {
                runOnUi(() -> {
                    selectedIndex.set(index);
                    playbackStatus.set(text.playbackPoint(index, total, sample, frame));
                });
            }

            @Override public void onPlaybackFailed(RuntimeException error) {
                reportAudioFailure(error);
            }
        });
        sonifier.addListener(new GraphSonificationListener() {
            @Override public void onFrameMapped(GraphAudioFrame frame) {
                if (!isLiveRunning()) return;
                String description = text.liveFrame(stock.name(), frame);
                runOnUi(() -> {
                    if (!isLiveRunning()) return;
                    liveStatus.set("재생 중 · " + description);
                    applicationStatus.accept(description);
                });
            }

            @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {
                reportAudioFailure(error);
            }

            @Override public void onFrameDropped(GraphAudioFrame frame) {
                reportAudioDrop();
            }
        });
    }

    private void reportAudioFailure(RuntimeException error) {
        runOnUi(() -> {
            String description = "청각 그래프 재생 실패: " + message(error);
            if (isLiveRunning()) liveStatus.set(description);
            else playbackStatus.set(description);
            applicationStatus.accept(description);
        });
    }

    private void reportAudioDrop() {
        runOnUi(() -> {
            String description = "오디오 처리 지연으로 대기 중이던 이전 그래프 지점 하나를 생략했습니다.";
            if (isLiveRunning()) liveStatus.set(description);
            else playbackStatus.set(description);
            applicationStatus.accept(description);
        });
    }

    private void handleLiveFailure(long generation, String prefix, RuntimeException failure) {
        if (!stopLiveInternal(generation, false)) return;
        runOnUi(() -> {
            String description = prefix + " · " + message(failure);
            liveStatus.set(description);
            applicationStatus.accept("실시간 청각 차트 " + description);
        });
    }

    private boolean stopLiveInternal(Long expectedGeneration, boolean reportCloseFailure) {
        EventSubscription subscription;
        boolean wasRunning;
        synchronized (liveLock) {
            if (expectedGeneration != null && !isLiveGenerationLocked(expectedGeneration)) return false;
            wasRunning = liveRunning || liveSubscription != null;
            liveRunning = false;
            liveGeneration++;
            lastLiveTimestamp = null;
            liveConnectionState = null;
            subscription = liveSubscription;
            liveSubscription = null;
        }
        try {
            sonifier.stop();
        } catch (RuntimeException failure) {
            if (reportCloseFailure) reportAudioFailure(failure);
        }
        closeSubscription(subscription, reportCloseFailure);
        return wasRunning;
    }

    private void closeSubscription(EventSubscription subscription, boolean reportFailure) {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (RuntimeException failure) {
            if (reportFailure) runOnUi(() -> {
                String description = "실시간 구독 해제 실패: " + message(failure);
                liveStatus.set(description);
                applicationStatus.accept(description);
            });
        }
    }

    private void reloadScale() {
        stopLiveInternal(null, false);
        activeScale = scaleMode == GraphScaleMode.AUTOMATIC
                ? GraphValueScale.automatic(samples)
                : GraphValueScale.percentFromReference(samples.get(0).value(), percentRange);
        GraphPlaybackPlan plan = planner.plan(
                samples, MAXIMUM_PLAYBACK_POINTS, TARGET_PLAYBACK_DURATION);
        playback.load(plan, activeScale);
        refreshScaleDescription();
    }

    private void refreshPointLabels() {
        pointLabels.clear();
        double reference = samples.get(0).value();
        for (int i = 0; i < samples.size(); i++) {
            pointLabels.add(text.pointLabel(i, samples.size(), samples.get(i), reference));
        }
    }

    private void refreshScaleDescription() {
        scaleDescription.set(text.scaleDescription(scaleMode, percentRange));
    }

    private void applyVolume() {
        if (closed.get()) return;
        audio.setVolume(speechActive ? volume * SPEECH_DUCKING_RATIO : volume);
    }

    private void notifyPreferencesChanged() {
        preferencesListener.accept(preferences());
    }

    private boolean isLiveGeneration(long generation) {
        synchronized (liveLock) { return isLiveGenerationLocked(generation); }
    }

    private boolean isLiveGenerationLocked(long generation) {
        return !closed.get() && liveRunning && generation == liveGeneration;
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("청각 차트가 이미 종료되었습니다.");
    }

    private void runOnUi(Runnable action) {
        try {
            uiExecutor.execute(action);
        } catch (RuntimeException ignored) {
            // 앱 종료 중 UI executor가 작업을 거부해도 시장 데이터 수신 스레드를 실패시키지 않는다.
        }
    }

    private static List<TimeSeriesSample> toSamples(String streamKey, List<Candle> candles) {
        List<Candle> checked = List.copyOf(Objects.requireNonNull(candles, "candles"));
        Instant previous = null;
        for (Candle candle : checked) {
            Objects.requireNonNull(candle, "candle");
            if (previous != null && candle.timestamp().isBefore(previous)) {
                throw new IllegalArgumentException("청각 차트 봉은 과거에서 최신 순이어야 합니다.");
            }
            previous = candle.timestamp();
        }
        return checked.stream()
                .map(candle -> new TimeSeriesSample(
                        streamKey, candle.close().doubleValue(), candle.timestamp()))
                .toList();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopLiveInternal(null, false);
        playback.close();
        sonifier.close();
    }
}
