package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.SimilarOutlook;
import org.ossproject.ai.SimilarStock;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 닮은 차트 화면.
 *
 * <p>이 화면이 하는 말은 하나다 — 과거 어느 구간이 지금과 모양이 닮았다. **예측이
 * 아니다.** 그 구분이 흐려지면 사용자는 닮은 종목을 대체재나 매수 후보로 읽는다.
 * 그래서 단서를 목록 위에 두고, 목록보다 먼저 읽히게 한다.
 *
 * <p>단서 문장은 서비스가 쓴 것을 그대로 쓴다. 같은 뜻으로 고쳐 쓰면 무엇이 서비스의
 * 주장이고 무엇이 우리 해석인지 사용자가 구별할 수 없다.
 */
public final class SimilarScreenView {

    private final String stockName;
    /** 한 종목을 읽어 준다. 음성 대기열은 앱이 관리한다. */
    private final BiConsumer<String, String> speak;
    /** 관심 종목에 담는다. 이미 있으면 앱이 알아서 알린다. */
    private final BiConsumer<String, String> addToWatchlist;
    /** 두 종목을 나란히 보여 준다. 코드와 이름을 넘긴다. */
    private final BiConsumer<String, String> compare;
    private final Consumer<Runnable> retry;

    private final VBox body = new VBox(14);

    public SimilarScreenView(String stockName, BiConsumer<String, String> speak,
                             BiConsumer<String, String> addToWatchlist,
                             BiConsumer<String, String> compare,
                             Consumer<Runnable> retry) {
        this.stockName = stockName == null || stockName.isBlank() ? "선택한 종목" : stockName;
        this.speak = Objects.requireNonNull(speak, "speak");
        this.addToWatchlist = Objects.requireNonNull(addToWatchlist, "addToWatchlist");
        this.compare = Objects.requireNonNull(compare, "compare");
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    public ScrollPane create() {
        loading();
        return scrollPage(stockName + "와 닮은 차트 화면", body);
    }

    /** 기다리는 중이라고 적는다. 빈 화면은 결과가 없는 것과 구별되지 않는다. */
    public void loading() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        Label text = new Label("닮은 차트를 찾고 있습니다.");
        HBox row = new HBox(10, spinner, text);
        row.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().setAll(heading(stockName + "와 유사한 항목"), row);
    }

    /**
     * 받지 못한 이유를 적고 다시 시도할 길을 준다.
     *
     * <p>빈 화면으로 두지 않는다. 닮은 차트가 없는 것과 조회에 실패한 것은 다른 뜻인데,
     * 아무것도 없으면 사용자는 닮은 종목이 없다고 읽는다.
     */
    public void unavailable(String reason) {
        Button again = new Button("다시 시도");
        again.setOnAction(event -> retry.accept(this::loading));
        body.getChildren().setAll(heading(stockName + "와 유사한 항목"),
                stateBanner(reason == null || reason.isBlank()
                        ? "닮은 차트를 받지 못했습니다." : reason, "warning"),
                again);
    }

    public void show(AiInsight insight) {
        body.getChildren().setAll(heading(insight.name() + "와 유사한 항목"));

        // 단서가 목록보다 먼저 온다. 뒤에 두면 카드를 다 듣고 나서야 이게 예측이 아니라는
        // 것을 알게 되는데, 그때는 이미 순위와 퍼센트가 머리에 남아 있다.
        body.getChildren().add(stateBanner(disclaimerOf(insight), "warning"));

        if (insight.similar().isEmpty()) {
            body.getChildren().add(new Label("닮은 차트를 찾지 못했습니다."));
            return;
        }

        int rank = 1;
        for (SimilarStock stock : insight.similar()) {
            body.getChildren().add(stockCard(rank++, insight.name(), stock));
        }

        // 닮은 구간 다음에 실제로 무슨 일이 있었는지. 유사도가 사실로 말할 수 있는 거의
        // 전부라 목록 아래 한 번 더 둔다.
        insight.similarOutlook().filter(SimilarOutlook::hasCounts).ifPresent(outlook ->
                body.getChildren().add(card("닮은 구간 다음에 있었던 일",
                        wrappingLabel(outlook.describe()))));
    }

    /** 서비스가 쓴 단서. 없으면 우리 문장으로 대신한다. 자리를 비우지는 않는다. */
    private static String disclaimerOf(AiInsight insight) {
        return insight.similarOutlook()
                .map(SimilarOutlook::disclaimer)
                .filter(text -> !text.isBlank())
                .orElse("유사도는 과거 모양이 닮았다는 뜻이며 미래 수익률과 무관합니다.");
    }

    private VBox stockCard(int rank, String queryName, SimilarStock stock) {
        Label badge = new Label(String.valueOf(rank));
        badge.getStyleClass().add("rank-badge");
        Label name = new Label(stock.name());
        name.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label score = new Label(stock.similarityPercent().stripTrailingZeros().toPlainString() + "%");
        score.getStyleClass().add("metric-value");
        HBox header = new HBox(10, badge, name, spacer, score);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(10, header);
        card.getStyleClass().add("panel-card");
        card.setPadding(new Insets(16));

        // 어디가 어떻게 닮았는지는 서비스가 문장으로 준다. 없으면 비워 둔다. 성분 숫자를
        // 보고 화면이 말을 지어내면 서비스가 쓴 것과 다른 근거가 된다.
        if (stock.hasExplanation()) {
            card.getChildren().add(wrappingLabel(stock.explanation()));
        }

        Button listen = new Button("같이 듣기");
        listen.setOnAction(event -> speak.accept(spokenOf(queryName, stock), "similar-stock"));
        Button watch = new Button("관심 종목에 추가");
        watch.setOnAction(event -> addToWatchlist.accept(stock.symbol(), stock.name()));
        Button compareButton = new Button("차트 비교 보기");
        compareButton.setOnAction(event -> compare.accept(stock.symbol(), stock.name()));

        card.getChildren().add(wrappingRow(8, listen, compareButton, watch));

        // 카드를 눌러도 차트가 열린다. 버튼만 두면 눈으로 보는 사람은 카드를 눌러 보고
        // 아무 일도 안 일어나 고장으로 읽는다.
        openChartOnActivate(card, stock, compareButton);
        card.setAccessibleText(rank + "위 " + stock.describe() + ". 누르면 차트를 비교합니다.");
        return card;
    }

    /**
     * 카드를 눌렀을 때 차트를 연다.
     *
     * <p>마우스뿐 아니라 키보드로도 열려야 한다. 클릭만 받으면 키보드로 다니는 사용자에게는
     * 이 카드가 없는 것과 같다. 그래서 초점을 받을 수 있게 하고 엔터와 스페이스를 함께 받는다.
     *
     * <p>버튼 위에서 누른 것은 흘려보낸다. 안 그러면 "관심 종목에 추가" 를 눌렀는데 차트까지
     * 열린다.
     */
    private static void openChartOnActivate(VBox card, SimilarStock stock, Button primary) {
        card.setFocusTraversable(true);
        card.getStyleClass().add("clickable-card");
        card.setOnMouseClicked(event -> {
            if (event.getTarget() instanceof Button) {
                return;
            }
            primary.fire();
        });
        card.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                primary.fire();
                event.consume();
            }
        });
    }

    /**
     * 읽어 줄 문장.
     *
     * <p>순위와 퍼센트만 읽으면 그 숫자가 무엇에 대한 것인지 남지 않는다. 무엇과 비교한
     * 것인지 먼저 말하고, 예측이 아니라는 사실로 닫는다.
     */
    private static String spokenOf(String queryName, SimilarStock stock) {
        StringBuilder sb = new StringBuilder(queryName + "와 닮은 차트, " + stock.describe() + ".");
        if (stock.hasExplanation()) {
            sb.append(' ').append(stock.explanation());
        }
        sb.append(" 과거 모양이 닮았다는 뜻이며 앞으로 같이 움직인다는 뜻이 아닙니다.");
        return sb.toString();
    }

    private static Label wrappingLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }
}
