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
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
                          Supplier<List<SpeechVoice>> availableVoices,
                          Supplier<List<String>> availableMicrophones) {
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
        tabs.setAccessibleHelp("Control+Tab 다음 설정 탭, Control+Shift+Tab 이전 설정 탭");

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
        body.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.TAB) {
                int count = tabs.getTabs().size();
                int current = Math.max(0, tabs.getSelectionModel().getSelectedIndex());
                int next = event.isShiftDown()
                        ? (current - 1 + count) % count
                        : (current + 1) % count;
                tabs.getSelectionModel().select(next);
                event.consume();
            }
        });
        VBox.setVgrow(centered, Priority.ALWAYS);
        return body;
    }

    /** 바뀐 값을 앱에 넘긴다. 적용도 저장도 앱이 한다. */
    private void change(AccessibilityPreferences updated) {
        current = updated;
        actions.onAccessibilityChanged().accept(updated);
    }

    private VBox accessibilityTab() {
        // "화면 읽기(TTS)" 라고 적혀 있었다. 그런데 이것을 꺼도 "듣기" 단추는 그대로
        // 읽어 준다 — 부른 것과 부르지 않아도 읽어 주는 것은 다른 일이라서다.
        // 이름이 실제로 하는 일과 어긋나면 사용자는 끄기를 주저한다.
        CheckBox speech = setting("자동으로 읽어 주기", preferences.speechEnabled(),
                value -> change(current.withSpeechEnabled(value)));
        speech.setAccessibleHelp("화면이 바뀌거나 목록을 옮길 때 스스로 읽어 줍니다. "
                + "꺼도 '듣기' 단추를 누르면 읽어 줍니다.");
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
        addBoundedSettingField(voiceSettings, 0, "음성", voice);
        addBoundedSettingField(voiceSettings, 1, "속도", speed);
        Label volumeTitle = new Label("음량");
        volumeTitle.setLabelFor(volume);
        Label volumeValue = new Label(Math.round(volume.getValue()) + "%");
        volumeValue.getStyleClass().add("volume-value");
        volume.valueProperty().addListener((observable, old, value) ->
                volumeValue.setText(Math.round(value.doubleValue()) + "%"));
        HBox volumeControl = new HBox(12, volume, volumeValue);
        volumeControl.setAlignment(Pos.CENTER_LEFT);
        // 큰 글자와 고대비를 함께 켜면 GridPane 이 남은 폭을 이 행에 모두 내주기도
        // 한다. 슬라이더까지 무한히 자라게 두면 막대가 왼쪽 제목 칸을 가로질러
        // 카드 끝까지 그려진다. 음량 조절에 충분한 폭만 명시적으로 사용한다.
        volumeControl.setMinWidth(0);
        volumeControl.setPrefWidth(520);
        volumeControl.setMaxWidth(520);
        volumeControl.getStyleClass().add("settings-volume-control");
        HBox.setHgrow(volume, Priority.ALWAYS);
        addBoundedSettingField(voiceSettings, 2, volumeTitle, volumeControl);
        addBoundedSettingField(voiceSettings, 3, "마이크", microphoneBox());
        voiceSettings.getColumnConstraints().addAll(
                expandingSettingLabelColumn(), boundedSettingControlColumn());

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

    /** 기본 장치를 뜻하는 항목. 빈 문자열을 그대로 보여 주면 무엇인지 알 수 없다. */
    private static final String SYSTEM_MICROPHONE = "시스템 기본 장치";

    /**
     * 마이크 고르기.
     *
     * <p>기본 장치를 알아서 쓰면 될 것 같지만 그렇지 않다. 실측에서 자바가 고른 기본
     * 장치는 소리가 하나도 들어오지 않는 "주 사운드 캡처 드라이버" 였고, 사용자가 실제로
     * 쓰던 이어폰은 목록의 다른 자리에 있었다. 그래서 고를 수 있어야 한다.
     *
     * <p>블루투스 이어폰은 껐다 켰다 하므로 목록이 열 때마다 달라진다. 저장된 장치가
     * 목록에 없으면 그것도 항목으로 넣어 둔다 — 빼 버리면 사용자가 고른 적 없는 장치로
     * 조용히 바뀐 것처럼 보인다.
     */
    private ComboBox<String> microphoneBox() {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add(SYSTEM_MICROPHONE);
        box.getItems().addAll(context.availableMicrophones().get());
        String saved = current.microphoneName();
        if (!saved.isBlank() && !box.getItems().contains(saved)) box.getItems().add(saved);
        box.setValue(saved.isBlank() ? SYSTEM_MICROPHONE : saved);
        box.setAccessibleText("음성 명령에 쓸 마이크");
        box.setAccessibleHelp("소리가 들어오지 않으면 다른 장치를 골라 보세요.");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setOnAction(event -> {
            String picked = box.getValue();
            change(current.withMicrophoneName(
                    picked == null || SYSTEM_MICROPHONE.equals(picked) ? "" : picked));
        });
        return box;
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
        slider.getStyleClass().add("volume-slider");
        slider.setMinWidth(0);
        slider.setPrefWidth(400);
        slider.setMaxWidth(420);
        // 숫자 눈금은 슬라이더의 실제 높이보다 크게 잡혀 고대비 초점 테두리와 다음 행을
        // 침범했다. 현재 값은 오른쪽의 퍼센트 글자로 보여 주고, 막대 자체는 한 줄로 둔다.
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        slider.setBlockIncrement(5);
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
        useBrowserLikeScrolling(scroll);
        return tab(title, scroll);
    }

    private static void addBoundedSettingField(GridPane form, int row,
                                               String labelText, Control control) {
        Label label = new Label(labelText);
        label.setLabelFor(control);
        addBoundedSettingField(form, row, label, control);
    }

    private static void addBoundedSettingField(GridPane form, int row,
                                               Label label, Region control) {
        control.setMinWidth(0);
        control.setPrefWidth(520);
        control.setMaxWidth(520);
        form.add(label, 0, row);
        form.add(control, 1, row);
        GridPane.setHgrow(control, Priority.NEVER);
        GridPane.setFillWidth(control, true);
    }

    /**
     * 왼쪽 제목 열만 남는 폭을 흡수한다. 긴 장치 이름이 있는 오른쪽 열을 비율 열로
     * 만들면 큰 글자에서 그리드의 계산 폭이 화면보다 커지고 슬라이더 트랙도 함께
     * 늘어나므로, 제어 열은 명시적으로 제한한다.
     */
    private static ColumnConstraints expandingSettingLabelColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setMinWidth(120);
        column.setPrefWidth(160);
        column.setMaxWidth(Double.MAX_VALUE);
        column.setHgrow(Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }

    private static ColumnConstraints boundedSettingControlColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setMinWidth(0);
        column.setPrefWidth(520);
        column.setMaxWidth(520);
        column.setHgrow(Priority.NEVER);
        column.setFillWidth(true);
        return column;
    }
}
