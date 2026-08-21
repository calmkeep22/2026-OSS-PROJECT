package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.ai.NewsArticle;
import org.ossproject.ai.NewsDigest;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 뉴스와 질의응답 화면.
 *
 * <p>둘을 한 화면에 둔 이유는 같은 것을 묻기 때문이다. 뉴스를 듣다 "이게 무슨 뜻이냐"
 * 가 나오는데, 그때 다른 화면으로 가야 하면 방금 들은 것을 잊는다.
 *
 * <p>감성 지수는 <b>여론의 방향을 요약한 값이지 주가 예측이 아니다.</b> 그 사실을 지수
 * 옆에 붙인다. 점수만 보이면 사용자는 그것을 신호로 읽는다.
 */
public final class NewsScreenView {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("MM월 dd일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final String stockName;
    private final BiConsumer<String, String> speak;
    /** 질문을 보낸다. 답은 화면 스레드로 돌아온다. */
    private final BiConsumer<String, Consumer<ChatAnswer>> ask;
    private final Runnable reload;

    private final VBox newsBody = new VBox(12);
    private final VBox chatLog = new VBox(10);
    private final VBox suggestionHost = new VBox(8);
    private final TextField question = new TextField();

    public NewsScreenView(String stockName, BiConsumer<String, String> speak,
                          BiConsumer<String, Consumer<ChatAnswer>> ask, Runnable reload) {
        this.stockName = stockName == null || stockName.isBlank() ? "선택한 종목" : stockName;
        this.speak = Objects.requireNonNull(speak, "speak");
        this.ask = Objects.requireNonNull(ask, "ask");
        this.reload = Objects.requireNonNull(reload, "reload");
    }

    public ScrollPane create() {
        loading();
        TabPane tabs = new TabPane(tab("뉴스", newsBody), tab("챗봇", chatPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setAccessibleText("뉴스와 챗봇 탭");
        VBox body = new VBox(14, heading(stockName + " 뉴스"), tabs);
        return scrollPage(stockName + " 뉴스와 챗봇 화면", body);
    }

    public void loading() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        HBox row = new HBox(10, spinner, new Label("뉴스를 받고 있습니다."));
        row.setAlignment(Pos.CENTER_LEFT);
        newsBody.getChildren().setAll(row);
    }

    /** 받지 못한 이유를 적고 다시 시도할 길을 준다. 빈 목록은 "뉴스 없음"으로 읽힌다. */
    public void unavailable(String reason) {
        Button again = new Button("다시 시도");
        again.setOnAction(event -> reload.run());
        newsBody.getChildren().setAll(stateBanner(reason == null || reason.isBlank()
                ? "뉴스를 받지 못했습니다." : reason, "warning"), again);
    }

    public void show(NewsDigest digest) {
        newsBody.getChildren().clear();

        // 지수를 먼저 둔다. 기사 제목만 훑으면 전체 논조가 어느 쪽인지 남지 않는다.
        digest.sentimentText().ifPresent(text -> {
            Button listen = new Button("브리핑 듣기");
            listen.setOnAction(event -> speak.accept(digest.briefing(), "news-briefing"));
            newsBody.getChildren().addAll(stateBanner(text, "info"),
                    wrappingRow(8, listen, countsLabel(digest)));
        });

        // 칸을 조용히 빼지 않는다. 사건 칸이 아예 없는 것과 사건이 없는 것은 다른 뜻인데,
        // 없어져 버리면 사용자는 화면이 덜 그려진 것인지 사건이 없는 것인지 알 수 없다.
        VBox events = new VBox(8);
        int index = 1;
        for (String event : digest.events()) {
            events.getChildren().add(eventRow(index++, event));
        }
        if (digest.events().isEmpty()) {
            events.getChildren().add(new Label("묶어 낼 만한 사건을 찾지 못했습니다."));
        }
        newsBody.getChildren().add(card("주요 사건", events));

        // 시황은 사건이 아니라 배경이다. 위치로 그 차이를 알린다.
        digest.marketLine().ifPresent(line ->
                newsBody.getChildren().add(card("시황", wrappingLabel(line))));

        VBox articles = new VBox(10);
        for (NewsArticle article : digest.articles()) {
            articles.getChildren().add(articleCard(article));
        }
        if (digest.articles().isEmpty()) {
            articles.getChildren().add(wrappingLabel(digest.briefing()));
        }
        newsBody.getChildren().add(card("기사", articles));
    }

    /**
     * 무엇을 몇 건 받았는지.
     *
     * <p>논조별 건수만 적으면 화면에 보이는 목록과 맞는지 알 수 없다. 받은 기사와 사건
     * 개수를 함께 적어 두면 화면이 덜 그려진 것인지 원래 없는 것인지 바로 드러난다.
     */
    private Label countsLabel(NewsDigest digest) {
        Label label = new Label("긍정 " + digest.positive() + "건 · 중립 " + digest.neutral()
                + "건 · 부정 " + digest.negative() + "건 · 받은 기사 " + digest.articles().size()
                + "건 · 사건 " + digest.events().size() + "개");
        label.getStyleClass().add("metric-detail");
        return label;
    }

    private VBox eventRow(int index, String event) {
        Button listen = new Button("요약 듣기");
        listen.setOnAction(action -> speak.accept(event, "news-event"));
        VBox row = new VBox(6, wrappingLabel(index + ". " + event), listen);
        row.setAccessibleText(index + "번째 사건. " + event);
        return row;
    }

    private VBox articleCard(NewsArticle article) {
        Label head = new Label(article.source()
                + (article.publishedAt() == null ? "" : " · " + CLOCK.format(article.publishedAt())));
        head.getStyleClass().add("metric-detail");
        Label title = wrappingLabel(article.title());
        title.getStyleClass().add("section-title");
        Button listen = new Button("요약 듣기");
        listen.setOnAction(event -> speak.accept(article.describe(), "news-article"));

        VBox card = new VBox(6, head, title, listen);
        card.getStyleClass().add("panel-card");
        card.setPadding(new Insets(14));
        card.setAccessibleText(article.describe());
        return card;
    }

    private VBox chatPane() {
        chatLog.setFillWidth(true);
        question.setPromptText("질문을 입력하세요");
        question.setOnAction(event -> send());
        HBox.setHgrow(question, Priority.ALWAYS);

        HBox input = new HBox(8, question, primaryButton("보내기", this::send));
        input.setAlignment(Pos.CENTER_LEFT);

        // 무엇을 물어야 할지 모르면 아무것도 못 묻는다. 답할 수 있는 것만 권한다.
        suggestions(List.of("쉽게 설명해줘", "핵심 수치 알려줘", "무슨 일이 있었어?"));

        Label footer = new Label("투자 추천 · 가격 예측은 제공하지 않습니다.");
        footer.getStyleClass().add("safety-note");
        footer.setWrapText(true);

        VBox pane = new VBox(12, chatLog, suggestionHost, input, footer);
        pane.setPadding(new Insets(4));
        return pane;
    }

    private void suggestions(List<String> picks) {
        if (picks.isEmpty()) {
            suggestionHost.getChildren().clear();
            return;
        }
        Button[] buttons = new Button[picks.size()];
        for (int i = 0; i < picks.size(); i++) {
            String pick = picks.get(i);
            Button button = new Button(pick);
            button.setOnAction(event -> {
                question.setText(pick);
                send();
            });
            buttons[i] = button;
        }
        suggestionHost.getChildren().setAll(wrappingRow(8, buttons));
    }

    private void send() {
        String text = question.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        question.clear();
        chatLog.getChildren().add(bubble("나", text, false));
        Label pending = new Label("답을 찾고 있습니다.");
        chatLog.getChildren().add(pending);
        ask.accept(text, answer -> {
            chatLog.getChildren().remove(pending);
            chatLog.getChildren().add(answerBubble(answer));
            if (!answer.suggestions().isEmpty()) {
                suggestions(answer.suggestions());
            }
        });
    }

    /**
     * 답 한 덩이.
     *
     * <p>답하지 않기로 한 것은 실패가 아니다. 오류처럼 보여 주면 사용자는 다시 물으면
     * 답이 나올 것으로 오해한다. 같은 모양으로 두되 근거 줄만 없다.
     */
    private VBox answerBubble(ChatAnswer answer) {
        VBox bubble = bubble("챗봇", answer.text(), true);
        if (!answer.groundsText().isBlank()) {
            Label grounds = new Label(answer.groundsText());
            grounds.getStyleClass().add("metric-detail");
            grounds.setWrapText(true);
            bubble.getChildren().add(grounds);
        }
        Button listen = new Button("답변 듣기");
        listen.setOnAction(event -> speak.accept(answer.text(), "chat-answer"));
        bubble.getChildren().add(listen);
        return bubble;
    }

    private VBox bubble(String who, String text, boolean fromBot) {
        Label name = new Label(who);
        name.getStyleClass().add("metric-label");
        VBox bubble = new VBox(4, name, wrappingLabel(text));
        bubble.getStyleClass().addAll("panel-card", fromBot ? "chat-bot" : "chat-user");
        bubble.setPadding(new Insets(12));
        bubble.setAccessibleText(who + ". " + text);
        return bubble;
    }

    private static Label wrappingLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(720);
        return label;
    }
}
