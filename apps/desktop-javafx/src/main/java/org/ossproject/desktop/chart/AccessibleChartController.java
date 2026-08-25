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
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.CandleInterval;
import org.ossproject.finance.model.market.Quote;
import org.ossproject.finance.model.SecurityId;
import org.ossproject.finance.model.market.StockDetail;
import org.ossproject.sonification.analysis.GraphAnalyzer;
import org.ossproject.sonification.playback.GraphPlaybackController;
import org.ossproject.sonification.playback.GraphPlaybackListener;
import org.ossproject.sonification.playback.GraphPlaybackPlan;
import org.ossproject.sonification.playback.GraphPlaybackPlanner;
import org.ossproject.sonification.playback.GraphPlaybackState;
import org.ossproject.sonification.playback.GraphSonificationListener;
import org.ossproject.sonification.analysis.LargestTriangleThreeBucketsReducer;
import org.ossproject.sonification.playback.StreamingGraphSonifier;
import org.ossproject.sonification.timing.EqualIntervalTimeMapping;
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
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns accessible-chart state and bridges application market events to the reusable audio core. */
public final class AccessibleChartController implements AutoCloseable {
    private static final int MAXIMUM_PLAYBACK_POINTS = 48;
    private static final Duration TARGET_PLAYBACK_DURATION = Duration.ofSeconds(12);
    private static final double SPEECH_DUCKING_RATIO = 0.15;

    /** A listening range and the standard candle interval that keeps it around 20–50 points. */
    public enum ListeningPeriod {
        DAY("1일", "5분봉", CandleInterval.MINUTE_5, 78) {
            @Override Instant cutoff(Instant latest, ZoneId zoneId) {
                return ZonedDateTime.ofInstant(latest, zoneId).minusDays(1).toInstant();
            }
        },
        WEEK("1주", "60분봉", CandleInterval.MINUTE_60, 35) {
            @Override Instant cutoff(Instant latest, ZoneId zoneId) {
                return ZonedDateTime.ofInstant(latest, zoneId).minusWeeks(1).toInstant();
            }
        },
        MONTH("1개월", "일봉", CandleInterval.DAY, 35) {
            @Override Instant cutoff(Instant latest, ZoneId zoneId) {
                return ZonedDateTime.ofInstant(latest, zoneId).minusMonths(1).toInstant();
            }
        },
        QUARTER("3개월", "일봉", CandleInterval.DAY, 80) {
            @Override Instant cutoff(Instant latest, ZoneId zoneId) {
                return ZonedDateTime.ofInstant(latest, zoneId).minusMonths(3).toInstant();
            }
        },
        YEAR("1년", "주봉", CandleInterval.WEEK, 60) {
            @Override Instant cutoff(Instant latest, ZoneId zoneId) {
                return ZonedDateTime.ofInstant(latest, zoneId).minusYears(1).toInstant();
            }
        };

        private final String label;
        private final String candleLabel;
        private final CandleInterval interval;
        private final int requestCount;

        ListeningPeriod(String label, String candleLabel,
                        CandleInterval interval,
                        int requestCount) {
            this.label = label;
            this.candleLabel = candleLabel;
            this.interval = interval;
            this.requestCount = requestCount;
        }
        public String label() { return label; }
        public String candleLabel() { return candleLabel; }
        CandleInterval interval() { return interval; }
        int requestCount() { return requestCount; }
        abstract Instant cutoff(Instant latest, ZoneId zoneId);
    }

    private final SecurityId security;
    private final String streamKey;
    private final MarketApplicationPort market;
    private final SonificationPort audio;
    /** 소리가 들리기까지의 시간. 화면 강조를 이만큼 늦춰 눈과 귀를 맞춘다. */
    private final java.time.Duration audioLatency;
    /** 기다리던 화면 갱신을 버릴 때 올린다. 멈추거나 건너뛰면 지난 갱신은 무효다. */
    private volatile long displayGeneration;
    private final StreamingGraphSonifier sonifier;
    private final GraphPlaybackController playback;
    private final GraphPlaybackPlanner planner = new GraphPlaybackPlanner(
            new LargestTriangleThreeBucketsReducer(), new EqualIntervalTimeMapping());
    private final ChartAnnouncementSink announcements;
    private final Consumer<String> applicationStatus;
    private final Executor uiExecutor;
    private final ChartTextFormatter text = new ChartTextFormatter(ZoneId.systemDefault());
    private final ObservableList<String> pointLabels = FXCollections.observableArrayList();
    private final SimpleStringProperty playbackStatus = new SimpleStringProperty("차트 데이터를 준비하는 중입니다.");
    private final SimpleStringProperty liveStatus = new SimpleStringProperty("중지됨");
    private final SimpleStringProperty scaleDescription = new SimpleStringProperty();
    private final SimpleStringProperty seriesDescription = new SimpleStringProperty();
    private final SimpleStringProperty summaryText = new SimpleStringProperty();
    private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object liveLock = new Object();

    private final StockDetail stock;
    private final Map<ListeningPeriod, List<TimeSeriesSample>> periodSamples =
            new EnumMap<>(ListeningPeriod.class);
    private List<TimeSeriesSample> samples;
    private List<TimeSeriesSample> playbackSamples = List.of();
    private GraphPlaybackPlan playbackPlan;
    private GraphSummary summary;
    private ListeningPeriod listeningPeriod = ListeningPeriod.MONTH;
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
        // 초기 화면은 일봉 스냅샷이다. 이후 기간을 바꾸면 enum의 표준 봉으로 다시 받는다.
        this.market = Objects.requireNonNull(market, "market");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.audioLatency = audio.outputLatency();
        this.announcements = Objects.requireNonNull(announcements, "announcements");
        this.applicationStatus = Objects.requireNonNull(applicationStatus, "applicationStatus");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.sonifier = new StreamingGraphSonifier(audio,
                new GraphSonificationConfig(220, 440, 880, 5.0, Duration.ofSeconds(1)));
        this.playback = new GraphPlaybackController(sonifier);
        List<TimeSeriesSample> initialSamples = toSamples(streamKey, candles);
        if (initialSamples.size() < 2) {
            throw new IllegalStateException("청각 차트에는 가격 지점이 두 개 이상 필요합니다.");
        }
        this.samples = samplesFor(listeningPeriod, initialSamples);
        periodSamples.put(listeningPeriod, samples);
        this.summary = GraphAnalyzer.summarize(samples);
        configureListeners();
        refreshDescriptions();
        refreshPointLabels();
        reloadScale();
        applyVolume();
    }

    public SecurityId security() { return security; }
    public StockDetail stock() { return stock; }
    public String seriesDescription() { return seriesDescription.get(); }
    public ReadOnlyStringProperty seriesDescriptionProperty() { return seriesDescription; }
    public ReadOnlyStringProperty summaryTextProperty() { return summaryText; }
    public ListeningPeriod listeningPeriod() { return listeningPeriod; }
    public List<TimeSeriesSample> samples() { return samples; }
    /** The reduced points that are actually played and visualized in the compact bar chart. */
    public List<TimeSeriesSample> playbackSamples() { return playbackSamples; }
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

    public String summaryText() { return summaryText.get(); }

    /**
     * Selects a period and its standard candle interval. Previously loaded periods are reused.
     * The returned stage completes after labels, bars and playback have all been refreshed.
     */
    public CompletionStage<Void> setListeningPeriod(ListeningPeriod period) {
        ListeningPeriod checked = Objects.requireNonNull(period, "period");
        if (checked == listeningPeriod) return CompletableFuture.completedFuture(null);
        stopLiveInternal(null, false);
        playback.stop();
        List<TimeSeriesSample> cached = periodSamples.get(checked);
        if (cached != null) {
            applyListeningPeriod(checked, cached);
            return CompletableFuture.completedFuture(null);
        }

        applicationStatus.accept(checked.label() + " " + checked.candleLabel()
                + " 데이터를 불러오고 있습니다.");
        CompletableFuture<Void> result = new CompletableFuture<>();
        market.loadCandles(security, checked.interval(), checked.requestCount())
                .whenComplete((candles, failure) -> runOnUi(() -> {
                    if (failure != null) {
                        String description = checked.label() + " 청각 차트 데이터를 불러오지 못했습니다.";
                        applicationStatus.accept(description);
                        result.completeExceptionally(failure);
                        return;
                    }
                    try {
                        List<TimeSeriesSample> loaded = samplesFor(
                                checked, toSamples(streamKey, candles));
                        if (loaded.size() < 2) {
                            throw new IllegalStateException("가격 지점이 두 개보다 적습니다.");
                        }
                        periodSamples.put(checked, loaded);
                        applyListeningPeriod(checked, loaded);
                        result.complete(null);
                    } catch (RuntimeException error) {
                        applicationStatus.accept(checked.label()
                                + " 청각 차트를 준비하지 못했습니다: " + message(error));
                        result.completeExceptionally(error);
                    }
                }));
        return result;
    }

    private void applyListeningPeriod(ListeningPeriod period, List<TimeSeriesSample> selectedSamples) {
        listeningPeriod = period;
        samples = selectedSamples;
        summary = GraphAnalyzer.summarize(samples);
        selectedIndex.set(-1);
        refreshDescriptions();
        refreshPointLabels();
        reloadScale();
        applicationStatus.accept(seriesDescription() + " 청각 차트를 준비했습니다.");
    }

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

    public void pause() {
        dropPendingPointDisplay();
        playback.pause();
    }
    public void stop() { playback.stop(); }

    /** Keeps the last exact point visible after pausing continuous playback. */
    public void showCurrentPointStatus() {
        int index = selectedIndex.get();
        if (index < 0 || index >= pointLabels.size()) return;
        playbackStatus.set(pointLabels.get(index));
    }

    public void replay() {
        dropPendingPointDisplay();
        stopLiveInternal(null, false);
        playback.replay();
        applicationStatus.accept("청각 차트를 처음부터 다시 재생합니다.");
    }

    public void seek(int index) {
        dropPendingPointDisplay();
        stopLiveInternal(null, false);
        playback.seek(index);
    }

    /** Moves to the source point represented by a bar in the reduced visual/audio chart. */
    public void seekPlaybackPoint(int playbackIndex) {
        GraphPlaybackPlan plan = playbackPlan;
        if (plan == null || playbackIndex < 0 || playbackIndex >= plan.playbackSamples().size()) return;
        seek(plan.sourceIndexForPlaybackIndex(playbackIndex));
    }

    /** Maps an exact source-point selection to the bar that represents it. */
    public int playbackIndexForSourceIndex(int sourceIndex) {
        GraphPlaybackPlan plan = playbackPlan;
        if (plan == null || sourceIndex < 0 || sourceIndex >= plan.sourceSamples().size()) return -1;
        return plan.playbackIndexAtOrBeforeSourceIndex(sourceIndex);
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

    /**
     * 소리가 들릴 때에 맞춰 화면을 옮긴다.
     *
     * <p>기다리는 시간은 출력 장치가 알려 준다. 여기에 숫자를 적어 두면 버퍼를 바꿨을
     * 때 그 숫자만 남아 어긋난다.
     *
     * <p>세대 번호로 묶는다. 멈추거나 다른 지점으로 건너뛰면 아직 기다리던 갱신이
     * 뒤늦게 도착해 방금 고른 자리를 덮어쓴다 — 손은 옮겼는데 화면이 되돌아가는
     * 것처럼 보인다.
     */
    private void showPointWhenAudible(Runnable update) {
        java.time.Duration latency = audioLatency;
        if (latency.isZero() || latency.isNegative()) {
            runOnUi(update);
            return;
        }
        long generation = displayGeneration;
        runOnUi(() -> {
            javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(latency.toMillis()));
            wait.setOnFinished(event -> {
                if (generation == displayGeneration) {
                    update.run();
                }
            });
            wait.play();
        });
    }

    /** 기다리던 화면 갱신을 무효로 만든다. 멈추거나 건너뛸 때 부른다. */
    private void dropPendingPointDisplay() {
        displayGeneration++;
    }

    public void announcePoint(int index) {
        if (index < 0 || index >= samples.size()) return;
        playback.pause();
        sonifier.stop();
        String description = text.exactPoint(samples.get(index), summary.first().value(),
                listeningPeriod.interval().isIntraday());
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
                    case PLAYING -> "거래 지점을 같은 간격으로 전체 그래프를 재생하고 있습니다.";
                    case PAUSED -> "일시정지됨 · 좌우 방향키로 가격 지점을 탐색할 수 있습니다.";
                    case COMPLETED -> "그래프 끝까지 재생했습니다. R 키로 다시 들을 수 있습니다.";
                }));
            }

            @Override public void onPointChanged(
                    int index, int total, TimeSeriesSample sample, GraphAudioFrame frame
            ) {
                // 소리는 출력 버퍼에 쌓였다가 나간다. 프레임을 넘긴 그 순간에 강조 표시를
                // 옮기면 눈이 귀보다 그만큼 앞선다. 실제로 들리는 때에 맞춰 옮긴다.
                showPointWhenAudible(() -> {
                    selectedIndex.set(index);
                    playbackStatus.set(text.playbackPoint(index, total, sample, frame,
                            listeningPeriod.interval().isIntraday()));
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
        playbackPlan = plan;
        playbackSamples = plan.playbackSamples();
        playback.load(plan, activeScale);
        refreshScaleDescription();
    }

    private void refreshPointLabels() {
        pointLabels.clear();
        double reference = samples.get(0).value();
        for (int i = 0; i < samples.size(); i++) {
            pointLabels.add(text.pointLabel(i, samples.size(), samples.get(i), reference,
                    listeningPeriod.interval().isIntraday()));
        }
    }

    private void refreshDescriptions() {
        String description = listeningPeriod.label() + " · " + listeningPeriod.candleLabel() + " "
                + samples.size() + "개 종가 · 약 " + TARGET_PLAYBACK_DURATION.toSeconds() + "초";
        seriesDescription.set(description);
        summaryText.set(text.summary(stock.name(), summary, description));
    }

    private List<TimeSeriesSample> samplesFor(
            ListeningPeriod period, List<TimeSeriesSample> sourceSamples
    ) {
        if (sourceSamples.size() < 2) return List.copyOf(sourceSamples);
        Instant latest = sourceSamples.get(sourceSamples.size() - 1).timestamp();
        Instant cutoff = period.cutoff(latest, ZoneId.systemDefault());
        List<TimeSeriesSample> filtered = sourceSamples.stream()
                .filter(sample -> !sample.timestamp().isBefore(cutoff))
                .toList();
        if (filtered.size() >= 2) return filtered;
        int from = Math.max(0, sourceSamples.size() - 2);
        return List.copyOf(sourceSamples.subList(from, sourceSamples.size()));
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
