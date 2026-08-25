package org.ossproject.desktop.view.screen;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ossproject.finance.model.account.Account;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.ossproject.desktop.view.UiKit.heading;
import static org.ossproject.desktop.view.UiKit.useBrowserLikeScrolling;
import static org.ossproject.desktop.view.UiKit.wrappingRow;

/** Builds the anomaly-monitoring screen without owning application navigation or persistence. */
public final class AnomalyScreenView {
    private final ObservableList<String> notifications;
    private final int monitoringCount;
    private final Supplier<Account> account;
    private final Node aiInsightPanel;
    private final Runnable manageWatchlist;
    private final BiConsumer<String, String> speak;
    private final Consumer<String> status;
    private final Runnable notificationsChanged;
    private final BiConsumer<String, String> showInformation;

    public AnomalyScreenView(ObservableList<String> notifications,
                             int monitoringCount,
                             Supplier<Account> account,
                             Node aiInsightPanel,
                             Runnable manageWatchlist,
                             BiConsumer<String, String> speak,
                             Consumer<String> status,
                             Runnable notificationsChanged,
                             BiConsumer<String, String> showInformation) {
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.monitoringCount = monitoringCount;
        this.account = Objects.requireNonNull(account, "account");
        this.aiInsightPanel = Objects.requireNonNull(aiInsightPanel, "aiInsightPanel");
        this.manageWatchlist = Objects.requireNonNull(manageWatchlist, "manageWatchlist");
        this.speak = Objects.requireNonNull(speak, "speak");
        this.status = Objects.requireNonNull(status, "status");
        this.notificationsChanged = Objects.requireNonNull(notificationsChanged, "notificationsChanged");
        this.showInformation = Objects.requireNonNull(showInformation, "showInformation");
    }

    public ScrollPane create() {
        Label title = heading("이상 감지");
        Label monitoring = new Label(monitoringCount == 0
                ? "보유종목이나 관심종목이 있으면 실시간 감시를 시작합니다."
                : "보유·관심종목 " + monitoringCount + "개를 실시간 감시 중입니다.");
        monitoring.getStyleClass().add("muted-text");
        VBox titleBlock = new VBox(2, title, monitoring);
        Label titleIcon = new Label("!");
        titleIcon.getStyleClass().add("anomaly-title-icon");
        // 감시 종목 관리 단추를 뺐다. 감시 대상은 보유·관심종목이 그대로 정하므로,
        // 여기서 또 고르게 하면 두 곳에서 관리하는 목록이 생긴다. 관심종목 화면이
        // 그 일을 이미 한다.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, titleIcon, titleBlock, spacer);
        header.setAlignment(Pos.CENTER_LEFT);

        FilteredList<String> signals = new FilteredList<>(notifications,
                value -> value.contains("· 이상 감지 ·"));
        StackPane urgentHost = new StackPane();
        Runnable renderUrgent = () -> urgentHost.getChildren().setAll(
                signals.isEmpty() ? emptyUrgentCard() : urgentCard(signals.get(0)));
        signals.addListener((javafx.collections.ListChangeListener<String>) change -> renderUrgent.run());
        renderUrgent.run();

        Set<String> holdingNames = new HashSet<>();
        FilteredList<String> holdingSignals = new FilteredList<>(signals,
                value -> holdingNames.stream().anyMatch(value::contains));
        FilteredList<String> watchlistSignals = new FilteredList<>(signals,
                value -> holdingNames.stream().noneMatch(value::contains));
        CompletableFuture.supplyAsync(account).whenComplete((snapshot, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) return;
                    holdingNames.clear();
                    snapshot.positions().forEach(position -> holdingNames.add(position.name()));
                    holdingSignals.setPredicate(value -> holdingNames.stream().anyMatch(value::contains));
                    watchlistSignals.setPredicate(value -> holdingNames.stream().noneMatch(value::contains));
                }));

        ListView<String> holdings = signalList(holdingSignals, "보유 종목 이상 신호가 없습니다.");
        ListView<String> watchlist = signalList(watchlistSignals, "관심 종목 이상 신호가 없습니다.");
        sizeToRows(holdings);
        sizeToRows(watchlist);

        Button listen = new Button("선택 신호 듣기");
        listen.setOnAction(event -> {
            String selected = selectedOf(holdings, watchlist);
            if (selected == null) {
                status.accept("들을 이상 감지 신호를 선택해주세요.");
                return;
            }
            speak.accept(selected, "anomaly-selected");
        });
        Button delete = new Button("선택 신호 지우기");
        delete.getStyleClass().add("danger-outline-button");
        delete.setOnAction(event -> {
            String selected = selectedOf(holdings, watchlist);
            if (selected == null) {
                status.accept("지울 이상 감지 신호를 선택해주세요.");
                return;
            }
            notifications.remove(selected);
            notificationsChanged.run();
            status.accept("선택한 이상 감지 신호를 지웠습니다.");
        });
        holdings.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) watchlist.getSelectionModel().clearSelection();
        });
        watchlist.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) holdings.getSelectionModel().clearSelection();
        });
        installSignalKeys(holdings, listen, delete);
        installSignalKeys(watchlist, listen, delete);

        VBox shell = new VBox(9, header, urgentHost, aiInsightPanel,
                section("보유 종목 알림", holdings), section("관심 종목 알림", watchlist),
                wrappingRow(8, listen, delete));
        shell.getStyleClass().addAll("anomaly-shell", "settings-shell");
        shell.setMaxWidth(1040);
        StackPane centered = new StackPane(shell);
        centered.setAlignment(Pos.TOP_CENTER);
        VBox body = new VBox(centered);
        body.getStyleClass().addAll("screen-content", "anomaly-screen");
        body.setPadding(new Insets(12));
        VBox.setVgrow(centered, Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setAccessibleText("이상 감지 화면");
        scroll.getStyleClass().add("workspace-scroll");
        useBrowserLikeScrolling(scroll);
        return scroll;
    }

    private static void installSignalKeys(ListView<String> list, Button listen, Button delete) {
        list.setAccessibleHelp("위아래 방향키로 신호 이동, Enter 또는 Space로 듣기, Delete로 지우기");
        list.setOnKeyPressed(event -> {
            if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)) {
                if (list.getSelectionModel().getSelectedItem() == null && !list.getItems().isEmpty()) {
                    list.getSelectionModel().selectFirst();
                }
                listen.fire();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE) {
                delete.fire();
                event.consume();
            }
        });
    }

    /** 한 줄 높이. 큰 글자 모드에서는 글자가 커진 만큼 줄도 키운다. */
    private static final double ROW_HEIGHT = 44;
    private static final double LARGE_ROW_HEIGHT = 60;


    /**
     * 목록 높이를 줄 수에 맞춘다.
     *
     * <p>픽셀로 못 박아 두었더니 높이가 줄 높이의 배수가 아니어서, 위아래 줄이 반씩
     * 잘린 채로 보였다. 큰 글자를 켜면 글자만 커지고 줄 수는 그대로라 더 심해졌다.
     * 호가표에서 겪은 것과 같은 문제다.
     *
     * <p>몇 줄이든 다 편다. 목록 안에 따로 스크롤을 두지 않는다 — 화면 안에 또 스크롤
     * 칸이 생기면 어느 것을 굴리고 있는지 알기 어렵고, 소리로 쓰는 사용자는 그 칸에
     * 들어갔다 나오는 것을 매번 확인해야 한다. 신호는 페이지째로 내려가며 본다.
     */
    private void sizeToRows(ListView<String> list) {
        list.sceneProperty().addListener((observed, oldScene, scene) -> {
            if (scene == null || scene.getRoot() == null) {
                return;
            }
            ObservableList<String> classes = scene.getRoot().getStyleClass();
            classes.addListener((javafx.collections.ListChangeListener<String>) change ->
                    applyRowHeight(list, classes.contains("large-text")));
            applyRowHeight(list, classes.contains("large-text"));
        });
        list.getItems().addListener((javafx.collections.ListChangeListener<String>) change ->
                applyRowHeight(list, list.getFixedCellSize() >= LARGE_ROW_HEIGHT));
        applyRowHeight(list, false);
    }

    private static void applyRowHeight(ListView<String> list, boolean largeText) {
        double row = largeText ? LARGE_ROW_HEIGHT : ROW_HEIGHT;
        list.setFixedCellSize(row);
        int rows = Math.max(1, list.getItems().size());
        // 두 픽셀은 목록 자신의 테두리 몫이다. 빼먹으면 마지막 줄이 한 획 잘린다.
        double height = rows * row + 2;
        list.setPrefHeight(height);
        list.setMinHeight(height);
        list.setMaxHeight(height);
    }

    private ListView<String> signalList(FilteredList<String> items, String emptyText) {
        ListView<String> list = new ListView<>(items);
        list.getStyleClass().add("anomaly-signal-list");
        list.setCellFactory(ignored -> signalCell());
        list.setPlaceholder(new Label(emptyText));
        list.setAccessibleText(emptyText.replace("없습니다.", "목록"));
        return list;
    }

    /**
     * 신호 한 건을 한 줄로 적는다.
     *
     * <p>원래는 제목·본문·배지를 세 줄로 쌓았는데, 제목과 본문이 같은 문장이라 같은
     * 말을 두 번 읽어 주고 있었다. 게다가 큰 글자를 켜면 카드 하나가 화면을 거의 다
     * 먹어, 저시력 사용자는 신호 하나를 보려고 화면을 여러 번 넘겨야 했다.
     *
     * <p>한 줄에 종목·종류·핵심 숫자·시각을 담는다. 정보를 버리는 것이 아니라 접어
     * 두는 것이다 — 문장 전체는 이 칸의 접근 가능한 이름에 그대로 남으므로 소리로
     * 듣는 쪽은 잃는 것이 없고, 고른 뒤 "선택 알림 듣기" 로 언제든 다시 들을 수 있다.
     *
     * <p>줄어드는 것은 문장뿐이다. 종목·배지·시각은 못 박아 둔다. 그것들이 눌리면
     * 어느 종목의 무슨 신호였는지가 사라진다.
     */
    private ListCell<String> signalCell() {
        return new ListCell<>() {
            private final Label name = new Label();
            private final Label time = new Label();
            private final Label message = new Label();
            private final Label badge = new Label();
            private final Region spacer = new Region();
            private final HBox card = new HBox(10, name, badge, message, spacer, time);
            {
                getStyleClass().add("anomaly-signal-cell");
                name.getStyleClass().add("anomaly-signal-name");
                time.getStyleClass().add("anomaly-signal-time");
                message.getStyleClass().add("anomaly-signal-message");
                badge.getStyleClass().add("anomaly-signal-badge");
                card.setAlignment(Pos.CENTER_LEFT);
                message.setMinWidth(0);
                message.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(message, Priority.ALWAYS);
                name.setMinWidth(Region.USE_PREF_SIZE);
                badge.setMinWidth(Region.USE_PREF_SIZE);
                time.setMinWidth(Region.USE_PREF_SIZE);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                card.getStyleClass().add("anomaly-signal-card");
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String content = messageOf(item);
                String kind = kindOf(content);
                String grade = gradeOf(content);
                name.setText(securityNameOf(content));
                time.setText(timeOf(item));
                message.setText(detailOf(content));
                badge.setText(kind);
                badge.getStyleClass().removeAll("badge-price-up", "badge-price-down", "badge-volume");
                badge.getStyleClass().add(content.contains("거래량") ? "badge-volume"
                        : content.contains("내렸") ? "badge-price-down" : "badge-price-up");
                // 화면에서는 한 줄로 줄었지만 읽어 줄 때는 온전한 문장으로 돌려준다.
                // 등급도 여기에 넣는다 — 배지 색으로만 알리면 소리로는 전해지지 않는다.
                setAccessibleText(name.getText() + ". " + kind
                        + (grade.isEmpty() ? "" : ". " + grade)
                        + ". " + message.getText() + ". " + time.getText());
                setText(null);
                setGraphic(card);
            }
        };
    }

    private VBox urgentCard(String signal) {
        String content = messageOf(signal);
        Label statusIcon = new Label("!");
        statusIcon.getStyleClass().addAll("anomaly-status-icon", "anomaly-status-alert");
        Label eyebrow = new Label("최근 이상 신호");
        Label message = new Label(content.replaceFirst("^(높음|주의) · ", ""));
        message.setWrapText(true);
        message.getStyleClass().add("anomaly-urgent-message");
        Label time = new Label(timeOf(signal));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, statusIcon, eyebrow, spacer, time);
        Button listen = new Button("소리로 듣기");
        listen.getStyleClass().add("primary-button");
        listen.setOnAction(event -> speak.accept(signal, "anomaly-latest"));
        // "자세히" 는 창을 띄우지 않고 이 자리에서 줄글을 펼친다. 대화상자는 열고
        // 닫는 두 걸음이 더 들고, 소리로 쓰는 사용자는 그때마다 창 안팎을 오가야 한다.
        Label detail = new Label(detailOf(content));
        detail.setWrapText(true);
        detail.getStyleClass().add("anomaly-signal-message");
        detail.setVisible(false);
        detail.setManaged(false);
        Button details = new Button("자세히");
        details.setAccessibleText("이 신호의 자세한 설명 펼치기");
        details.setOnAction(event -> {
            boolean show = !detail.isVisible();
            detail.setVisible(show);
            detail.setManaged(show);
            details.setText(show ? "접기" : "자세히");
            details.setAccessibleText(show
                    ? "이 신호의 자세한 설명 접기"
                    : "이 신호의 자세한 설명 펼치기");
            // 펼친 것을 눈으로만 알리면 소리로 쓰는 사용자는 아무 일도 안 일어난 줄 안다.
            if (show) {
                speak.accept(detail.getText(), "anomaly-detail");
            }
        });
        VBox card = new VBox(6, top, message, detail, wrappingRow(7, listen, details));
        card.getStyleClass().add(content.startsWith("높음")
                ? "anomaly-urgent-high" : "anomaly-urgent-card");
        return card;
    }

    private VBox emptyUrgentCard() {
        Label statusIcon = new Label("i");
        statusIcon.getStyleClass().addAll("anomaly-status-icon", "anomaly-status-normal");
        Label title = new Label("현재 긴급 이상 신호가 없습니다.");
        title.getStyleClass().add("anomaly-urgent-message");
        Label detail = new Label(monitoringCount == 0
                ? "관심종목을 추가하면 자동 감지를 시작합니다."
                : "실시간 시세를 감시하고 있습니다.");
        detail.getStyleClass().add("muted-text");
        VBox copy = new VBox(4, title, detail);
        HBox row = new HBox(10, statusIcon, copy);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(row);
        card.getStyleClass().add("anomaly-urgent-card");
        return card;
    }

    private static VBox section(String title, ListView<String> list) {
        Label label = new Label(title);
        label.getStyleClass().add("anomaly-section-title");
        return new VBox(5, label, list);
    }

    private static String selectedOf(ListView<String> first, ListView<String> second) {
        String selected = first.getSelectionModel().getSelectedItem();
        return selected == null ? second.getSelectionModel().getSelectedItem() : selected;
    }

    static String messageOf(String notification) {
        String normalized = notification.replaceFirst("^새 알림 · ", "");
        int category = normalized.indexOf(" · 이상 감지 · ");
        return category < 0 ? normalized : normalized.substring(category + " · 이상 감지 · ".length());
    }

    static String timeOf(String notification) {
        String normalized = notification.replaceFirst("^새 알림 · ", "");
        int separator = normalized.indexOf(" · ");
        return separator < 0 ? "" : normalized.substring(0, separator);
    }

    /**
     * 종목명.
     *
     * <p>알림은 "동화약품 · 주의 · 동화약품 최근 1분 거래량이 …" 처럼 종목명이 앞머리와
     * 문장에 두 번 들어온다. 예전에는 " 최근 " 앞을 통째로 잘라 "동화약품 · 주의 ·
     * 동화약품" 을 이름으로 삼았고, 본문은 같은 문장을 또 적었다. 스크린리더는 제목과
     * 본문을 잇달아 읽으므로 같은 말을 두 번 듣게 됐다.
     *
     * <p>첫 마디만 가져온다. 등급이 앞에 오는 옛 형식이면 그 뒤가 이름이다.
     */
    static String securityNameOf(String content) {
        String plain = content.replaceFirst("^(높음|주의) · ", "");
        int separator = plain.indexOf(" · ");
        if (separator > 0) {
            return plain.substring(0, separator);
        }
        int recent = plain.indexOf(" 최근 ");
        return recent > 0 ? plain.substring(0, recent) : "이상 신호";
    }

    /** 등급. 없으면 빈 문자열. */
    static String gradeOf(String content) {
        for (String grade : new String[] {"높음", "주의"}) {
            if (content.startsWith(grade + " · ") || content.contains(" · " + grade + " · ")) {
                return grade;
            }
        }
        return "";
    }

    /**
     * 앞머리를 걷어낸 본문.
     *
     * <p>종목명과 등급은 이미 따로 보여 준다. 문장에 한 번 더 붙어 있으면 같은 말을
     * 세 번 적는 셈이다.
     */
    static String detailOf(String content) {
        String plain = content.replaceFirst("^(높음|주의) · ", "");
        String name = securityNameOf(content);
        if (!"이상 신호".equals(name)) {
            String quoted = java.util.regex.Pattern.quote(name);
            plain = plain.replaceFirst("^" + quoted + " · (높음|주의) · ", "");
            plain = plain.replaceFirst("^" + quoted + " · ", "");
            plain = plain.replaceFirst("^" + quoted + " ", "");
        }
        return plain;
    }

    /** 무슨 종류의 신호인지. 배지에 적고 소리로도 읽어 준다. */
    static String kindOf(String content) {
        return content.contains("거래량") ? "거래량 급증"
                : content.contains("내렸") ? "가격 급락" : "가격 급등";
    }
}
