package org.ossproject.desktop.view.screen;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.ossproject.accessibility.notification.SpeechVoice;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.state.AccessibilityPreferences;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 설정 화면.
 *
 * <p>이 화면은 값을 바꿔 돌려주기만 한다. 합성기에 넣는 일도, 저장하는 일도 앱이 맡는다.
 * 화면이 직접 {@code SpeechOptions} 를 조립해 합성기에 넣고 저장은 다른 곳에서 하면
 * 한쪽만 도는 경우가 생긴다 — 소리는 바뀌었는데 다음 실행 때 되돌아가거나 그 반대다.
 *
 * <p>그래서 접근성 설정 일곱 가지가 콜백 하나로 나간다. 낱개로 넘기면 새 설정을 더할
 * 때마다 생성자가 늘어나고, 무엇에 의존하는지 읽히지 않는다.
 *
 * <p>화면 스타일 분류(큰 글자, 고대비 …)도 여기서 만지지 않는다. 그것은 앱의 뿌리 노드에
 * 걸리는 것이라 이 화면이 닿을 자리가 아니다.
 */
public final class SettingsScreenView {

    /**
     * 화면이 읽기만 하는 사실들.
     *
     * <p>낱개 인자로 늘어놓으면 생성자가 길어지고 순서를 잘못 넣어도 컴파일이 된다.
     *
     * @param realtimeStatus    실시간 연결 상태. 다른 화면과 같은 값을 물려 쓴다
     * @param subscriptionCount 지금 구독 수
     * @param maskedAccountNo   계좌번호를 가린 문자열. 조회에 시간이 걸려 나중에 온다
     */
    public record Context(String marketDataSource, String secretProtection,
                          ReadOnlyStringProperty realtimeStatus,
                          ReadOnlyStringProperty subscriptionCount,
                          Supplier<String> maskedAccountNo,
                          Supplier<List<SpeechVoice>> availableVoices) {
    }

    /** 화면이 앱에 부탁하는 일들. */
    public record Actions(Consumer<AccessibilityPreferences> onAccessibilityChanged,
                          Consumer<Boolean> onPreventDuplicateChanged,
                          Consumer<String> onPreview,
                          Runnable onAudit,
                          Consumer<Screen> onNavigate,
                          Consumer<String> onStatus) {
    }

    private static final SpeechVoice SYSTEM_DEFAULT =
            new SpeechVoice("", "시스템 기본 음성", "");

    private final AccessibilityPreferences preferences;
    private final boolean preventDuplicateOrders;
    private final Context context;
    private final Actions actions;

    /** 화면이 값을 바꿀 때마다 여기서 쌓고 통째로 돌려준다. */
    private AccessibilityPreferences current;

    public SettingsScreenView(AccessibilityPreferences preferences, boolean preventDuplicateOrders,
                              Context context, Actions actions) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.current = preferences;
        this.preventDuplicateOrders = preventDuplicateOrders;
        this.context = Objects.requireNonNull(context, "context");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    public VBox create() {
        TabPane tabs = new TabPane(
                settingsTab("접근성", accessibilityTab()),
                settingsTab("연결·보안", connectionTab()),
                settingsTab("알림", notificationTab()),
                settingsTab("고급 설정", advancedTab()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setTabMinWidth(140);
        tabs.setMinHeight(0);
        tabs.setMaxHeight(Double.MAX_VALUE);
        tabs.getStyleClass().add("settings-tabs");
        // 탭 묶음에 이름이 없으면 스크린리더가 "탭 목록" 이라고만 읽는다. 무엇에 대한
        // 탭인지 알 수 없다.
        tabs.setAccessibleText("설정 탭");

        Label description = new Label("음성, 화면, 연결과 거래 안전 설정을 관리합니다.");
        description.getStyleClass().add("muted-text");
        VBox shell = new VBox(10, new VBox(2, heading("설정"), description), tabs);
        shell.getStyleClass().add("settings-shell");
        shell.setMaxWidth(1040);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        StackPane centered = new StackPane(shell);
        centered.setAlignment(Pos.TOP_CENTER);
        VBox body = new VBox(centered);
        body.getStyleClass().addAll("screen-content", "settings-screen");
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0);
        VBox.setVgrow(centered, Priority.ALWAYS);
        return body;
    }

    /** 바뀐 값을 앱에 넘긴다. 적용도 저장도 앱이 한다. */
    private void change(AccessibilityPreferences updated) {
        current = updated;
        actions.onAccessibilityChanged().accept(updated);
    }

    private VBox accessibilityTab() {
        CheckBox speech = setting("화면 읽기(TTS)", preferences.speechEnabled(),
                value -> change(current.withSpeechEnabled(value)));
        CheckBox keyboard = setting("키보드 탐색 안내", preferences.keyboardGuidanceEnabled(),
                value -> change(current.withKeyboardGuidanceEnabled(value)));
        CheckBox reducedMotion = setting("그림자·시각 효과 줄이기", preferences.reducedMotionEnabled(),
                value -> change(current.withReducedMotionEnabled(value)));
        CheckBox largeText = setting("큰 글자", preferences.largeTextEnabled(),
                value -> change(current.withLargeTextEnabled(value)));
        CheckBox contrast = setting("고대비", preferences.highContrastEnabled(),
                value -> change(current.withHighContrastEnabled(value)));
        List.of(speech, keyboard, reducedMotion, largeText, contrast)
                .forEach(control -> control.getStyleClass().add("settings-switch"));

        ComboBox<SpeechVoice> voice = voiceBox();
        ComboBox<String> speed = speedBox();
        Slider volume = volumeSlider();

        GridPane voiceSettings = new GridPane();
        voiceSettings.setHgap(16);
        voiceSettings.setVgap(10);
        addField(voiceSettings, 0, "음성", voice);
        addField(voiceSettings, 1, "속도", speed);
        addField(voiceSettings, 2, "음량", volume);
        voiceSettings.getColumnConstraints().addAll(equalColumn(), equalColumn());

        Button preview = new Button("설정 미리 듣기");
        preview.setOnAction(event -> actions.onPreview().accept(
                "음성 설정 미리 듣기입니다. 현재 속도는 " + speed.getValue()
                        + "이고 정보량은 " + current.informationDensity() + "입니다."));
        Button audit = new Button("현재 화면 접근성 검사");
        audit.setOnAction(event -> actions.onAudit().run());

        return settingsTabContent(
                settingsCard("화면 접근성", speech, keyboard, reducedMotion, largeText, contrast),
                settingsCard("음성 설정", voiceSettings, wrappingRow(8, preview, audit)),
                stateBanner("변경 사항은 선택 즉시 적용되고 자동 저장됩니다.", "success"));
    }

    /**
     * 음성 고르기.
     *
     * <p>목록을 받아 오는 데 시간이 걸린다. 화면 스레드에서 부르면 설정 화면을 여는 동안
     * 앱이 멈춘다.
     */
    private ComboBox<SpeechVoice> voiceBox() {
        ComboBox<SpeechVoice> box = new ComboBox<>();
        box.getItems().add(SYSTEM_DEFAULT);
        box.setValue(SYSTEM_DEFAULT);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(SpeechVoice value) {
                if (value == null) {
                    return "";
                }
                return value.language().isBlank() ? value.displayName()
                        : value.displayName() + " (" + value.language() + ")";
            }

            @Override
            public SpeechVoice fromString(String value) {
                return SYSTEM_DEFAULT;
            }
        });
        box.valueProperty().addListener((observable, old, selected) -> {
            if (selected != null) {
                change(current.withVoiceName(selected.id()));
            }
        });

        CompletableFuture.supplyAsync(context.availableVoices())
                .whenComplete((voices, failure) -> Platform.runLater(() -> {
                    if (failure != null || voices == null) {
                        actions.onStatus().accept(
                                "음성 목록을 불러오지 못했습니다. 시스템 기본 음성을 사용합니다.");
                        return;
                    }
                    SpeechVoice selected = voices.stream()
                            .filter(item -> Objects.equals(item.id(), preferences.voiceName()))
                            .findFirst().orElse(SYSTEM_DEFAULT);
                    box.getItems().setAll(SYSTEM_DEFAULT);
                    box.getItems().addAll(voices);
                    box.setValue(selected);
                }));
        return box;
    }

    private ComboBox<String> speedBox() {
        ComboBox<String> box = new ComboBox<>(
                FXCollections.observableArrayList("0.8배", "1.0배", "1.2배", "1.5배"));
        box.setValue(String.format(Locale.ROOT, "%.1f배", preferences.speechRate()));
        box.valueProperty().addListener((observable, old, selected) -> {
            if (selected != null) {
                change(current.withSpeechRate(Double.parseDouble(selected.replace("배", ""))));
            }
        });
        return box;
    }

    private Slider volumeSlider() {
        Slider slider = new Slider(0, 100, preferences.speechVolume());
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit(25);
        // 옆 라벨을 labelFor 로 걸어도 슬라이더 자신에게는 이름이 남지 않는다. 초점이
        // 들어왔을 때 "슬라이더 40" 만 들리면 무엇의 40인지 알 수 없다.
        slider.setAccessibleText("음성 음량");
        slider.valueProperty().addListener((observable, old, value) ->
                change(current.withSpeechVolume(value.intValue())));
        return slider;
    }

    private VBox connectionTab() {
        Label realtime = new Label();
        realtime.textProperty().bind(context.realtimeStatus());
        Label account = new Label("조회 중");
        // 계좌 조회는 네트워크를 탄다. 화면을 여는 동안 앱이 멈추면 안 된다.
        CompletableFuture.supplyAsync(context.maskedAccountNo())
                .whenComplete((masked, failure) -> Platform.runLater(() ->
                        account.setText(failure == null ? masked : "연결 후 자동 조회")));

        return settingsTabContent(
                settingsCard("키움 API 연결",
                        labeledControl("연결 상태", realtime),
                        informationRow("현재 공급원", context.marketDataSource()),
                        informationRow("계좌", account),
                        primaryButton("연결 설정 열기",
                                () -> actions.onNavigate().accept(Screen.CONNECTION))),
                settingsCard("개인정보·보안",
                        informationRow("비밀 저장 보호", context.secretProtection()),
                        informationRow("모의/실전 자격증명", "완전 분리"),
                        informationRow("로그 계좌번호", "마스킹"),
                        informationRow("토큰 평문 저장", "사용 안 함"),
                        new Label("App Secret과 토큰은 화면이나 일반 설정 파일에 표시·저장하지 않습니다.")));
    }

    private VBox notificationTab() {
        CheckBox sound = setting("앱 효과음", preferences.soundEnabled(),
                value -> change(current.withSoundEnabled(value)));
        sound.getStyleClass().add("settings-switch");

        Label subscriptions = new Label();
        subscriptions.textProperty().bind(context.subscriptionCount());

        return settingsTabContent(
                settingsCard("소리 알림", sound,
                        new Label("이상 감지 경고음과 주문 성공·오류 등 앱 효과음을 함께 켜거나 끕니다.")),
                settingsCard("실시간 알림 데이터",
                        labeledControl("현재 구독", subscriptions),
                        new Label("화면용 구독은 닫을 때 해제하고, 관심종목 이상 감시는 앱 실행 동안 유지합니다."),
                        primaryButton("알림 화면 열기",
                                () -> actions.onNavigate().accept(Screen.NOTIFICATIONS))));
    }

    private VBox advancedTab() {
        ComboBox<String> density = new ComboBox<>(
                FXCollections.observableArrayList("좁게", "표준", "넓게"));
        density.setValue(preferences.informationDensity());
        density.valueProperty().addListener((observable, old, selected) -> {
            if (selected != null) {
                change(current.withInformationDensity(selected));
            }
        });
        GridPane densitySetting = new GridPane();
        densitySetting.setHgap(16);
        densitySetting.setVgap(8);
        addField(densitySetting, 0, "화면 밀도", density);

        CheckBox preventDuplicate = new CheckBox("같은 주문의 연속 입력 방지");
        preventDuplicate.setSelected(preventDuplicateOrders);
        preventDuplicate.getStyleClass().addAll("setting-toggle", "settings-switch");
        preventDuplicate.selectedProperty().addListener((observable, old, value) ->
                actions.onPreventDuplicateChanged().accept(value));

        return settingsTabContent(
                settingsCard("화면 표시", densitySetting),
                settingsCard("거래 안전",
                        stateBanner("모든 신규·취소 주문은 항상 재확인합니다.", "success"),
                        preventDuplicate,
                        informationRow("주문 계좌", "키움 토큰에 연결된 계좌 자동 사용")));
    }

    private static VBox settingsCard(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("settings-card-heading");
        VBox card = new VBox(9, heading);
        card.getChildren().addAll(content);
        card.getStyleClass().add("settings-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static VBox settingsTabContent(Node... content) {
        VBox panel = new VBox(10, content);
        panel.getStyleClass().add("settings-tab-content");
        panel.setFillWidth(true);
        return panel;
    }

    private static Tab settingsTab(String title, Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("settings-tab-scroll");
        return tab(title, scroll);
    }

    private static ColumnConstraints equalColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(50);
        column.setHgrow(Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }
}
