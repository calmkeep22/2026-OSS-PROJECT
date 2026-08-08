package org.ossproject.desktop.chart;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.ossproject.application.port.StockQueryPort;
import org.ossproject.desktop.radio.FakeMarketRadioFeed;
import org.ossproject.finance.model.PricePeriod;
import org.ossproject.finance.model.StockDetail;
import org.ossproject.sonification.*;
import org.ossproject.sonification.model.*;
import org.ossproject.sonification.port.SonificationPort;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns accessible-chart state and coordinates historical playback, seeking, and live samples. */
public final class AccessibleChartController implements AutoCloseable {
    private static final String SYMBOL = "005930";
    private static final int MAXIMUM_PLAYBACK_POINTS = 48;
    private static final Duration TARGET_PLAYBACK_DURATION = Duration.ofSeconds(12);
    private static final double SPEECH_DUCKING_RATIO = 0.15;

    private final StockQueryPort stocks;
    private final SonificationPort audio;
    private final StreamingGraphSonifier sonifier;
    private final GraphPlaybackController playback;
    private final FakeMarketRadioFeed liveFeed;
    private final GraphPlaybackPlanner planner = new GraphPlaybackPlanner(
            new LargestTriangleThreeBucketsReducer(), new TimestampProportionalTimeMapping());
    private final ChartAnnouncementSink announcements;
    private final Consumer<String> applicationStatus;
    private final ChartTextFormatter text = new ChartTextFormatter(ZoneId.systemDefault());
    private final ObservableList<String> pointLabels = FXCollections.observableArrayList();
    private final SimpleStringProperty playbackStatus = new SimpleStringProperty("차트 데이터를 준비하는 중입니다.");
    private final SimpleStringProperty liveStatus = new SimpleStringProperty("중지됨");
    private final SimpleStringProperty scaleDescription = new SimpleStringProperty();
    private final IntegerProperty selectedIndex = new SimpleIntegerProperty(-1);
    private final AtomicBoolean closed = new AtomicBoolean();

    private final StockDetail stock;
    private final List<TimeSeriesSample> samples;
    private final GraphSummary summary;
    private GraphScaleMode scaleMode = GraphScaleMode.AUTOMATIC;
    private double percentRange = 5.0;
    private volatile double volume = 0.8;
    private volatile boolean speechActive;

    public AccessibleChartController(StockQueryPort stocks, SonificationPort audio,
                                     ChartAnnouncementSink announcements,
                                     Consumer<String> applicationStatus) {
        this.stocks = Objects.requireNonNull(stocks, "stocks");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.announcements = Objects.requireNonNull(announcements, "announcements");
        this.applicationStatus = Objects.requireNonNull(applicationStatus, "applicationStatus");
        this.sonifier = new StreamingGraphSonifier(audio,
                new GraphSonificationConfig(220, 440, 880, 5.0, Duration.ofSeconds(1)));
        this.playback = new GraphPlaybackController(sonifier);
        this.stock = this.stocks.getDetail(SYMBOL);
        this.samples = this.stocks.getPriceHistory(SYMBOL, PricePeriod.MONTH).stream()
                .map(point -> new TimeSeriesSample(SYMBOL, point.close().doubleValue(),
                        point.date().atTime(LocalTime.of(15, 30)).atZone(ZoneId.systemDefault()).toInstant()))
                .toList();
        this.summary = GraphAnalyzer.summarize(samples);
        this.liveFeed = new FakeMarketRadioFeed(SYMBOL,
                List.of(70_800d, 71_600d, 70_500d, 72_400d, 72_600d, 73_100d, 72_900d, 73_800d, 73_500d),
                Duration.ofSeconds(1), sonifier::accept, this::handleLiveFeedFailure);
        configureListeners();
        refreshPointLabels();
        reloadScale();
        applyVolume();
    }

    public StockDetail stock() { return stock; }
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
    public String summaryText() { return text.summary(stock.name(), summary); }

    public void play() {
        liveFeed.stop();
        playback.play();
        applicationStatus.accept("청각 차트 전체 그래프를 재생합니다.");
    }

    public void pause() { playback.pause(); }
    public void stop() { playback.stop(); }

    public void replay() {
        liveFeed.stop();
        playback.replay();
        applicationStatus.accept("청각 차트를 처음부터 다시 재생합니다.");
    }

    public void seek(int index) {
        liveFeed.stop();
        playback.seek(index);
    }

    public void setSpeed(double speed) { playback.setSpeed(speed); }

    public void setScaleMode(GraphScaleMode mode) {
        GraphScaleMode checked = Objects.requireNonNull(mode, "mode");
        if (checked == scaleMode) return;
        scaleMode = checked;
        reloadScale();
    }

    public void setPercentRange(double percentRange) {
        if (!Double.isFinite(percentRange) || percentRange <= 0 || percentRange >= 100) {
            throw new IllegalArgumentException("percentRange must be between zero and one hundred");
        }
        this.percentRange = percentRange;
        if (scaleMode == GraphScaleMode.PERCENT_FROM_REFERENCE) reloadScale();
        else refreshScaleDescription();
    }

    public void setVolume(double volume) {
        if (!Double.isFinite(volume) || volume < 0 || volume > 1) {
            throw new IllegalArgumentException("volume must be between zero and one");
        }
        this.volume = volume;
        applyVolume();
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

    public void startLive() {
        try {
            if (liveFeed.isRunning()) return;
            playback.pause();
            sonifier.stop();
            sonifier.start(stock.symbol());
            liveFeed.start();
            liveStatus.set("재생 중 · " + stock.name() + " 가격 그래프를 연속음으로 듣고 있습니다.");
            applicationStatus.accept("실시간 청각 차트 모니터링을 시작했습니다.");
        } catch (RuntimeException failure) {
            sonifier.stop();
            liveStatus.set("시작 실패 · " + message(failure));
            applicationStatus.accept("실시간 모니터링 시작 실패: " + message(failure));
        }
    }

    public void stopLive() {
        boolean wasRunning = liveFeed.isRunning();
        liveFeed.stop();
        if (wasRunning) sonifier.stop();
        liveStatus.set("중지됨");
        applicationStatus.accept("실시간 청각 차트 모니터링을 중지했습니다.");
    }

    private void configureListeners() {
        playback.addListener(new GraphPlaybackListener() {
            @Override public void onStateChanged(GraphPlaybackState state) {
                runOnFxThread(() -> playbackStatus.set(switch (state) {
                    case EMPTY -> "차트 데이터가 없습니다.";
                    case READY -> "재생 준비됨 · Space 또는 전체 그래프 재생 버튼을 누르세요.";
                    case PLAYING -> "실제 날짜 간격을 반영해 전체 그래프를 재생하고 있습니다.";
                    case PAUSED -> "일시정지됨 · 좌우 방향키로 가격 지점을 탐색할 수 있습니다.";
                    case COMPLETED -> "그래프 끝까지 재생했습니다. R 키로 다시 들을 수 있습니다.";
                }));
            }

            @Override public void onPointChanged(int index, int total, TimeSeriesSample sample,
                                                 GraphAudioFrame frame) {
                runOnFxThread(() -> {
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
                if (!liveFeed.isRunning()) return;
                String description = text.liveFrame(stock.name(), frame);
                runOnFxThread(() -> {
                    liveStatus.set("재생 중 · " + description);
                    applicationStatus.accept(description);
                });
            }

            @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {
                reportAudioFailure(error);
            }
        });
    }

    private void reportAudioFailure(RuntimeException error) {
        runOnFxThread(() -> {
            String description = "청각 그래프 재생 실패: " + message(error);
            if (liveFeed.isRunning()) liveStatus.set(description);
            else playbackStatus.set(description);
            applicationStatus.accept(description);
        });
    }

    private void handleLiveFeedFailure(RuntimeException failure) {
        runOnFxThread(() -> {
            stopLive();
            liveStatus.set("데이터 재생 실패 · " + message(failure));
            applicationStatus.accept("실시간 모니터링 데이터 재생 실패: " + message(failure));
        });
    }

    private void reloadScale() {
        liveFeed.stop();
        GraphValueScale scale = scaleMode == GraphScaleMode.AUTOMATIC
                ? GraphValueScale.automatic(samples)
                : GraphValueScale.percentFromReference(samples.get(0).value(), percentRange);
        GraphPlaybackPlan plan = planner.plan(samples, MAXIMUM_PLAYBACK_POINTS, TARGET_PLAYBACK_DURATION);
        playback.load(plan, scale);
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
        liveFeed.close();
        playback.close();
        sonifier.close();
    }
}
