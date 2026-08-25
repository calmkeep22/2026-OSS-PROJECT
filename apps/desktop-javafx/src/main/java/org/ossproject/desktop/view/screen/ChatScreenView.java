package org.ossproject.desktop.view.screen;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.ChatAnswer;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.useBrowserLikeScrolling;

/**
 * 앱 어디서든 같은 기록으로 이어 쓰는 AI 질문 화면.
 *
 * <p>물어볼 것을 고르게 한다. 자유롭게 입력받지 않는다. 뒤에 있는 것은 말을 알아듣는
 * 모형이 아니라 낱말로 의도를 가르는 분기라, 답할 수 있는 것이 다섯 갈래로 정해져
 * 있다. 빈 입력창은 무엇이든 물어도 되는 것처럼 보이지만 대부분 "이해하지 못했습니다"
 * 로 끝난다. 그 값은 화면을 볼 수 없는 사용자에게 특히 비싸다 — 입력하고, 기다리고,
 * 답을 못 받고, 무엇을 물어야 하는지는 여전히 모른다.
 *
 * <p>고를 것을 늘어놓으면 스크린리더가 세어 준다. 막다른 길이 없고, 이 앱이 무엇을
 * 아는지도 그 목록이 그대로 말해 준다.
 *
 * <p>목록은 답마다 바뀌지 않는다. 매번 달라지면 방금 있던 자리에 다른 것이 놓여,
 * 눈으로 훑지 않는 사용자는 늘 처음부터 다시 읽어야 한다.
 *
 * <p>종목 화면마다 새 대화를 만들지 않는다. 질문을 보내는 시점에 앱이 가진 최신 분석을
 * 근거로 쓰되, 대화 기록은 하나로 보존한다. 답하지 못하거나 거절한 답도 기록에서
 * 조용히 사라지지 않는다.
 */
public final class ChatScreenView {
    private final BiConsumer<String, String> speak;
    private final BiConsumer<String, Consumer<ChatAnswer>> ask;
    /**
     * 초점이 닿은 질문을 읽어 줄 곳.
     *
     * <p>{@code speak} 와 나눈다. 그쪽은 사용자가 "읽어 줘" 라고 부른 자리라 화면
     * 읽기가 꺼져 있으면 그 사실을 알리는데, 화살표를 누를 때마다 그 경고가 나가면
     * 질문을 훑을 수가 없다. 여기서는 꺼져 있으면 그냥 조용하다.
     */
    private Consumer<String> speakFocus = text -> { };
    private final VBox chatLog = new VBox(12);
    private final FlowPane suggestionHost = new FlowPane(8, 8);
    private final Button latestAnswer = new Button("최근 답변 듣기 (Alt+L)");
    private final ScrollPane messages = new ScrollPane(chatLog);

    private BorderPane root;
    private String latestAnswerText = "";
    /** 지금 초점이 닿아 있는 질문. 묶음을 떠났다 돌아와도 그 자리를 지킨다. */
    private int focusedSuggestion;
    /** 방금 읽어 준 질문. 초점 신호와 옮기기가 겹쳐도 두 번 읽지 않게 한다. */
    private int spokenSuggestion = -1;
    private final List<String> suggestionNames = new java.util.ArrayList<>();

    public ChatScreenView(BiConsumer<String, String> speak,
                          BiConsumer<String, Consumer<ChatAnswer>> ask) {
        this.speak = Objects.requireNonNull(speak, "speak");
        this.ask = Objects.requireNonNull(ask, "ask");
    }

    /** 이전 호출부와의 소스 호환용. 화면은 더 이상 종목별 제목을 만들지 않는다. */
    public ChatScreenView(String ignoredStockName, BiConsumer<String, String> speak,
                          BiConsumer<String, Consumer<ChatAnswer>> ask) {
        this(speak, ask);
    }

    /**
     * 물어볼 수 있는 것의 전부.
     *
     * <p>뒤쪽은 낱말로 의도를 가른다. 그래서 문구를 마음대로 바꾸면 갈 곳을 잃고
     * "이해하지 못했습니다" 로 떨어진다. 괄호 안이 그 낱말이다.
     *
     * <p>순서도 뜻이 있다. 뒤쪽은 뉴스·위험·움직임·수치·설명 순으로 가르므로, 앞선
     * 갈래의 낱말이 뒤 문구에 섞이면 엉뚱한 답이 나간다. 예를 들어 "변동" 은 위험
     * 갈래의 낱말이라 움직임을 묻는 문구에 쓸 수 없다.
     */
    private static final List<String> ASKABLE = List.of(
            "쉽게 설명해줘",        // 설명해
            "핵심 수치 알려줘",      // 수치
            "무슨 일이 있었어?",     // 무슨 일
            "위험도가 어때?",        // 위험
            "얼마나 움직일지 예측해줘" // 예측
    );

    /** 아직 받은 답이 있는지. 단축키가 "없습니다" 를 대신 말해 줄 수 있게 묻는다. */
    public boolean hasAnswer() {
        return !latestAnswerText.isBlank();
    }

    /**
     * 가장 최근 답을 읽어 준다.
     *
     * <p>단추와 같은 길을 쓴다. 여기서만 따로 읽으면 나중에 한쪽 문구만 고쳐져 둘이
     * 어긋난다.
     */
    public void listenToLatestAnswer() {
        latestAnswer.fire();
    }

    /** 초점이 닿은 질문을 읽어 줄 곳을 건다. 걸지 않으면 조용히 지나간다. */
    public void setFocusSpeaker(Consumer<String> speaker) {
        this.speakFocus = speaker == null ? text -> { } : speaker;
    }

    public Node create() {
        if (root != null) {
            return root;
        }

        Label title = new Label("AI 챗봇");
        title.getStyleClass().add("chat-title");
        Label scope = new Label("검증된 AI 분석과 수집된 뉴스만 근거로 답합니다. "
                + "답할 수 있는 것은 아래 " + ASKABLE.size() + "가지입니다.");
        scope.getStyleClass().add("chat-scope");
        scope.setWrapText(true);
        VBox titleBlock = new VBox(3, title, scope);

        latestAnswer.setDisable(true);
        latestAnswer.getStyleClass().add("chat-listen-button");
        latestAnswer.setAccessibleText("가장 최근 챗봇 답변 듣기");
        latestAnswer.setOnAction(event -> speak.accept(latestAnswerText, "chat-latest-answer"));
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(12, titleBlock, headerSpacer, latestAnswer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("chat-header");

        chatLog.setFillWidth(true);
        chatLog.getStyleClass().add("chat-log");
        chatLog.getChildren().add(messageRow(
                bubble("챗봇", "아래에서 물어볼 것을 고르세요. 지금 고른 종목의 핵심 수치, "
                        + "위험도, 최근 뉴스처럼 앱에서 확인한 사실을 쉽게 설명해 드립니다. "
                        + "사고파는 판단과 앞으로의 가격은 답하지 않습니다.", true), false));
        messages.setFitToWidth(true);
        messages.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        messages.getStyleClass().add("chat-log-scroll");
        messages.setAccessibleText("AI 챗봇 대화 기록");
        useBrowserLikeScrolling(messages);

        suggestions(ASKABLE);
        Label suggestionLabel = new Label("무엇을 물어볼까요");
        suggestionLabel.getStyleClass().add("chat-suggestion-label");
        // 목록 자체에 개수를 실어 둔다. 스크린리더가 묶음을 만나는 순간 몇 개인지
        // 알아야, 하나씩 넘겨 보기 전에 전체 크기를 가늠할 수 있다.
        suggestionHost.setAccessibleText("물어볼 질문 " + ASKABLE.size() + "가지");

        Label footer = new Label("투자 추천 · 매수·매도 판단 · 가격 예측은 제공하지 않습니다.");
        footer.getStyleClass().add("chat-footer");
        footer.setWrapText(true);
        VBox composer = new VBox(8, suggestionLabel, suggestionHost, footer);
        composer.getStyleClass().add("chat-composer");

        root = new BorderPane(messages, header, null, composer, null);
        root.getStyleClass().addAll("screen-content", "chat-screen");
        root.setPadding(new Insets(16));
        root.setAccessibleText("AI 챗봇 화면. 앱 전체에서 이어지는 하나의 대화입니다.");
        root.setAccessibleHelp("질문에서는 방향키로 이동하고 Enter 또는 Space로 묻습니다. "
                + "Control+Enter는 현재 선택한 질문을 묻고, Control+L은 대화 기록으로 이동합니다.");
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                List<Node> chips = suggestionHost.getChildren();
                if (!chips.isEmpty() && chips.get(focusedSuggestion) instanceof Button button) {
                    button.fire();
                    event.consume();
                }
            } else if (event.isControlDown() && event.getCode() == KeyCode.L) {
                messages.requestFocus();
                event.consume();
            }
        });
        BorderPane.setMargin(messages, new Insets(12, 0, 12, 0));
        return root;
    }

    /**
     * 물어볼 것을 늘어놓는다.
     *
     * <p>묶음 전체가 탭 한 번이고, 그 안은 화살표로 옮긴다. 칩마다 탭을 세우면 마지막
     * 질문까지 다섯 번을 눌러야 하고, 소리로 쓰는 사용자에게 탭 한 번은 "여기가
     * 어디인지" 를 매번 다시 확인하는 일이다. 툴바와 라디오 묶음이 쓰는 방식과 같다.
     *
     * <p>초점이 닿은 질문은 읽어 준다. 무엇 위에 서 있는지 모르면 화살표로 옮겨도
     * 소용이 없다.
     */
    private void suggestions(List<String> picks) {
        suggestionHost.getChildren().clear();
        suggestionNames.clear();
        for (int i = 0; i < picks.size(); i++) {
            String pick = picks.get(i);
            int index = i;
            Button button = new Button(pick);
            button.getStyleClass().add("chat-suggestion-chip");
            // 몇 개 중 몇 번째인지 함께 읽어 준다. 눈으로 보면 한눈에 세어지는 것이
            // 소리로는 세어지지 않는다.
            String name = pick + ", 질문 " + picks.size() + "가지 중 " + (i + 1) + "번째";
            button.setAccessibleText(name);
            // 묶음에 들어오는 문은 하나뿐이다. 나머지는 화살표로만 닿는다.
            button.setFocusTraversable(i == 0);
            button.setOnAction(event -> send(pick));
            suggestionNames.add(name);
            button.focusedProperty().addListener((observed, had, has) -> {
                if (has) {
                    focusedSuggestion = index;
                    announceSuggestion(index);
                }
            });
            button.addEventFilter(KeyEvent.KEY_PRESSED, this::moveWithinSuggestions);
            suggestionHost.getChildren().add(button);
        }
        focusedSuggestion = 0;
        spokenSuggestion = -1;
        suggestionHost.setVisible(!picks.isEmpty());
        suggestionHost.setManaged(!picks.isEmpty());
    }

    /** 화살표로 질문 사이를 옮긴다. 양 끝에서는 반대편으로 돌아간다. */
    private void moveWithinSuggestions(KeyEvent event) {
        List<Node> chips = suggestionHost.getChildren();
        if (chips.isEmpty()) {
            return;
        }
        int target = switch (event.getCode()) {
            case LEFT, UP -> (focusedSuggestion - 1 + chips.size()) % chips.size();
            case RIGHT, DOWN -> (focusedSuggestion + 1) % chips.size();
            case HOME -> 0;
            case END -> chips.size() - 1;
            default -> -1;
        };
        if (target < 0) {
            return;
        }
        focusSuggestion(target);
        event.consume();
    }

    /**
     * 질문 묶음으로 들어간다.
     *
     * <p>탭으로 들어올 수 있는 칩은 하나뿐이므로, 그 자리를 옮겨 준 뒤 초점을 준다.
     * 그러지 않으면 다음에 탭으로 들어올 때 늘 첫 질문으로 되돌아간다.
     */
    public void focusQuestions() {
        focusSuggestion(Math.max(0, Math.min(focusedSuggestion, suggestionHost.getChildren().size() - 1)));
    }

    private void focusSuggestion(int index) {
        List<Node> chips = suggestionHost.getChildren();
        if (index < 0 || index >= chips.size()) {
            return;
        }
        for (int i = 0; i < chips.size(); i++) {
            chips.get(i).setFocusTraversable(i == index);
        }
        focusedSuggestion = index;
        chips.get(index).requestFocus();
        // 초점이 실제로 옮겨졌는지에 기대지 않는다. 창이 아직 화면에 올라오지 않았거나
        // 다른 곳이 초점을 쥐고 있으면 requestFocus 는 조용히 지나간다. 그래도 사용자는
        // 어디로 옮겼는지 들어야 한다.
        announceSuggestion(index);
    }

    /** 같은 질문을 두 번 읽지 않는다. 초점 신호와 옮기기가 겹쳐 들어올 수 있다. */
    private void announceSuggestion(int index) {
        if (index == spokenSuggestion || index < 0 || index >= suggestionNames.size()) {
            return;
        }
        spokenSuggestion = index;
        speakFocus.accept(suggestionNames.get(index));
    }

    /**
     * 대화 기록에 종목이 바뀐 자리를 남긴다.
     *
     * <p>기록은 하나로 이어지는데 답은 그때그때 고른 종목에 대한 것이다. 표시가 없으면
     * 위쪽 답이 어느 종목 이야기였는지 알 길이 없다 — 특히 되짚어 읽을 때 그렇다.
     */
    public void noteStock(String stockName) {
        if (stockName == null || stockName.isBlank() || root == null) {
            return;
        }
        Label marker = new Label("여기부터 " + stockName + "에 대해 답합니다.");
        marker.getStyleClass().add("chat-stock-marker");
        marker.setWrapText(true);
        marker.setAccessibleText(marker.getText());
        HBox row = new HBox(marker);
        row.setAlignment(Pos.CENTER_LEFT);
        chatLog.getChildren().add(row);
        scrollToLatest();
    }

    private void send(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        chatLog.getChildren().add(messageRow(bubble("나", text.trim(), false), true));
        Label pending = new Label("답을 찾고 있습니다.");
        pending.getStyleClass().add("chat-pending");
        HBox pendingRow = new HBox(pending);
        pendingRow.setAlignment(Pos.CENTER_LEFT);
        chatLog.getChildren().add(pendingRow);
        scrollToLatest();

        ask.accept(text.trim(), answer -> {
            chatLog.getChildren().remove(pendingRow);
            chatLog.getChildren().add(messageRow(answerBubble(answer), false));
            latestAnswerText = answer.text();
            latestAnswer.setDisable(false);
            // 답에 딸려 오는 추천으로 목록을 갈아 끼우지 않는다. 물어볼 수 있는 것은
            // 늘 같은 다섯 가지이고, 자리가 바뀌면 소리로 훑는 사용자가 매번 처음부터
            // 다시 읽어야 한다.
            scrollToLatest();
        });
    }

    private VBox answerBubble(ChatAnswer answer) {
        VBox bubble = bubble("챗봇", answer.text(), true);
        if (!answer.groundsText().isBlank()) {
            Label grounds = new Label(answer.groundsText());
            grounds.getStyleClass().add("chat-grounds");
            grounds.setWrapText(true);
            bubble.getChildren().add(grounds);
        }
        Button listen = new Button("답변 듣기");
        listen.getStyleClass().add("chat-answer-listen");
        listen.setAccessibleText("이 챗봇 답변 듣기");
        listen.setOnAction(event -> speak.accept(answer.text(), "chat-answer"));
        bubble.getChildren().add(listen);
        return bubble;
    }

    private static HBox messageRow(VBox bubble, boolean fromUser) {
        HBox row = new HBox(bubble);
        row.setAlignment(fromUser ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        row.getStyleClass().addAll("chat-message-row",
                fromUser ? "chat-message-user" : "chat-message-bot");
        return row;
    }

    private static VBox bubble(String who, String text, boolean fromBot) {
        Label name = new Label(who);
        name.getStyleClass().add("chat-speaker");
        VBox bubble = new VBox(6, name, wrappingLabel(text));
        bubble.getStyleClass().addAll("chat-bubble", fromBot ? "chat-bot" : "chat-user");
        bubble.setPadding(new Insets(12, 14, 12, 14));
        bubble.setMaxWidth(760);
        bubble.setAccessibleText(who + ". " + text);
        return bubble;
    }

    private void scrollToLatest() {
        Platform.runLater(() -> messages.setVvalue(1.0));
    }

    private static Label wrappingLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(720);
        label.getStyleClass().add("chat-message-text");
        return label;
    }
}
