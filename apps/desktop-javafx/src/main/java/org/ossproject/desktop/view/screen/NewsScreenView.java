package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.NewsArticle;
import org.ossproject.ai.NewsDigest;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 종목 뉴스 화면.
 *
 * <p>감성 지수는 <b>여론의 방향을 요약한 값이지 주가 예측이 아니다.</b> 그 사실을 지수
 * 옆에 붙인다. 점수만 보이면 사용자는 그것을 신호로 읽는다.
 */
public final class NewsScreenView {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("MM월 dd일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final String stockName;
    private final BiConsumer<String, String> speak;
    private final Runnable reload;
    private final Consumer<String> openArticle;

    private final VBox newsBody = new VBox(12);
    private final List<VBox> articleCards = new ArrayList<>();
    private int focusedArticle;

    public NewsScreenView(String stockName, BiConsumer<String, String> speak, Runnable reload) {
        this(stockName, speak, reload, ignored -> { });
    }

    public NewsScreenView(String stockName, BiConsumer<String, String> speak, Runnable reload,
                          Consumer<String> openArticle) {
        this.stockName = stockName == null || stockName.isBlank() ? "선택한 종목" : stockName;
        this.speak = Objects.requireNonNull(speak, "speak");
        this.reload = Objects.requireNonNull(reload, "reload");
        this.openArticle = Objects.requireNonNull(openArticle, "openArticle");
    }

    public ScrollPane create() {
        loading();
        // 종목명은 상단 바의 현재 위치와 이 화면의 접근성 이름(scrollPage 첫 인자)에
        // 이미 들어 있다. 큰 제목으로 한 번 더 쓰면 화면만 좁아지고, 스크린리더는
        // 같은 이름을 두 번 읽는다.
        VBox body = new VBox(14, newsBody);
        ScrollPane page = scrollPage(stockName + " 뉴스 화면", body);
        page.setAccessibleHelp("기사에서는 위아래 방향키로 이동하고, Enter로 원문을 열며, "
                + "Space로 요약을 듣습니다. Control+R로 뉴스를 다시 받습니다.");
        page.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.R) {
                reload.run();
                event.consume();
            }
        });
        return page;
    }

    public void loading() {
        articleCards.clear();
        focusedArticle = 0;
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        HBox row = new HBox(10, spinner, new Label("뉴스를 받고 있습니다."));
        row.setAlignment(Pos.CENTER_LEFT);
        newsBody.getChildren().setAll(row);
    }

    /** 받지 못한 이유를 적고 다시 시도할 길을 준다. 빈 목록은 "뉴스 없음"으로 읽힌다. */
    public void unavailable(String reason) {
        articleCards.clear();
        focusedArticle = 0;
        Button again = new Button("다시 시도");
        again.setOnAction(event -> reload.run());
        newsBody.getChildren().setAll(stateBanner(reason == null || reason.isBlank()
                ? "뉴스를 받지 못했습니다." : reason, "warning"), again);
    }

    public void show(NewsDigest digest) {
        newsBody.getChildren().clear();
        articleCards.clear();
        focusedArticle = 0;

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
            VBox articleCard = articleCard(article);
            articleCards.add(articleCard);
            articles.getChildren().add(articleCard);
        }
        if (!articleCards.isEmpty()) articleCards.get(0).setFocusTraversable(true);
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
        Button original = new Button("원문 열기");
        original.getStyleClass().add("primary-button");
        original.setAccessibleText(article.source() + " 기사 원문을 기본 브라우저에서 열기");
        original.setOnAction(event -> openArticle.accept(article.url()));
        boolean hasUrl = article.url() != null && !article.url().isBlank();
        original.setVisible(hasUrl);
        original.setManaged(hasUrl);
        Button listen = new Button("요약 듣기");
        listen.setOnAction(event -> speak.accept(article.describe(), "news-article"));

        VBox card = new VBox(8, head, title, wrappingRow(8, original, listen));
        card.getStyleClass().add("panel-card");
        card.setPadding(new Insets(14));
        card.setAccessibleText(article.describe());
        card.setAccessibleHelp("위아래 방향키로 기사를 이동합니다. Enter는 원문 열기, Space는 요약 듣기입니다.");
        card.setFocusTraversable(false);
        card.focusedProperty().addListener((observable, old, focused) -> {
            if (focused) focusedArticle = articleCards.indexOf(card);
        });
        card.setOnMouseClicked(event -> {
            if (!(event.getTarget() instanceof Button)) card.requestFocus();
        });
        card.setOnKeyPressed(event -> {
            int target = switch (event.getCode()) {
                case UP -> Math.max(0, focusedArticle - 1);
                case DOWN -> Math.min(articleCards.size() - 1, focusedArticle + 1);
                case HOME -> 0;
                case END -> articleCards.size() - 1;
                default -> -1;
            };
            if (target >= 0) {
                focusArticle(target);
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER && hasUrl) {
                original.fire();
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                listen.fire();
                event.consume();
            }
        });
        return card;
    }

    private void focusArticle(int index) {
        if (index < 0 || index >= articleCards.size()) return;
        for (int i = 0; i < articleCards.size(); i++) {
            articleCards.get(i).setFocusTraversable(i == index);
        }
        focusedArticle = index;
        articleCards.get(index).requestFocus();
    }

    private static Label wrappingLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(720);
        return label;
    }
}
