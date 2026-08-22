package org.ossproject.desktop.ai;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.AiInsight;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 보유·관심 종목의 AI 분석을 한 줄씩 늘어놓는다.
 *
 * <p>이 칸이 붙는 이상 감지 화면은 종목 하나를 들여다보는 곳이 아니라 여러 종목을 훑는
 * 곳이다. 한 종목짜리 카드를 맨 위에 두면 아래 목록과 아무 관계 없는 정보가 제일 먼저
 * 읽힌다. 게다가 그 화면에는 종목 선택기가 없어서, 사용자는 문안을 끝까지 들어야 어느
 * 종목인지 알 수 있었다.
 *
 * <p>순서를 바꾸지 않는다. 이상한 종목을 위로 올리고 싶지만, 읽는 도중에 목록이 재배열
 * 되면 스크린리더 사용자는 방금 어디를 듣고 있었는지 잃는다. 대신 몇 건이 이상한지 위에
 * 세어 둔다.
 *
 * <p>한 종목이 실패해도 그 자리에 이유를 적고 넘어간다. 하나 때문에 전부 못 보면 이
 * 화면은 쓸 수 없다.
 */
public final class AiInsightListPanel {

    private final Consumer<String> onListen;
    private final VBox root = new VBox(12);
    private final Label headline = new Label();
    private final VBox cards = new VBox(12);
    private final ProgressIndicator spinner = new ProgressIndicator();
    /** 종목 코드 -> 그 종목 카드. 결과가 도착한 순서와 상관없이 제자리에 넣는다. */
    private final Map<String, AiInsightCard> bySymbol = new LinkedHashMap<>();

    private int total;
    private int done;
    private int unusual;
    private int failed;

    public AiInsightListPanel(Consumer<String> onListen) {
        this.onListen = Objects.requireNonNull(onListen, "onListen");
        headline.getStyleClass().add("card-title");
        headline.setWrapText(true);
        headline.setMinHeight(Region.USE_PREF_SIZE);
        spinner.setPrefSize(20, 20);
        HBox header = new HBox(10, headline, spinner);
        header.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(header, cards);
        waiting();
    }

    public javafx.scene.Node root() {
        return root;
    }

    /** 감시할 종목이 하나도 없을 때. 빈 칸으로 두면 분석이 실패한 것처럼 보인다. */
    public void empty(String reason) {
        total = 0;
        done = 0;
        spinner.setVisible(false);
        spinner.setManaged(false);
        headline.setText(reason);
        cards.getChildren().clear();
    }

    public void waiting() {
        headline.setText("AI 분석을 기다리고 있습니다.");
        spinner.setVisible(true);
        spinner.setManaged(true);
        cards.getChildren().clear();
        bySymbol.clear();
        total = 0;
        done = 0;
        unusual = 0;
        failed = 0;
    }

    /**
     * 서버가 아직 준비 중이거나 쓸 수 없다.
     *
     * <p>기능을 감추지 않고 이유를 적는다. 아무것도 없으면 사용자는 이 화면에 원래 AI
     * 분석이 없는 것으로 읽는다.
     */
    public void unavailable(String reason, Runnable onRetry) {
        empty(reason == null || reason.isBlank() ? "AI 분석을 사용할 수 없습니다." : reason);
        Button again = new Button("다시 시도");
        again.setOnAction(event -> onRetry.run());
        cards.getChildren().add(again);
    }

    /**
     * 분석을 시작한다. 자리를 먼저 만들어 둔다.
     *
     * <p>결과가 오는 대로 카드를 붙이면 목록이 위아래로 흔들린다. 스무 종목이면 3초 동안
     * 그 흔들림이 이어지고, 스크린리더는 그때마다 처음부터 다시 읽는다.
     */
    public void starting(java.util.List<String> symbols, java.util.List<String> names) {
        waiting();
        total = symbols.size();
        for (int i = 0; i < symbols.size(); i++) {
            AiInsightCard card = new AiInsightCard(names.get(i), onListen);
            bySymbol.put(symbols.get(i), card);
            cards.getChildren().add(card.root());
        }
        updateHeadline();
    }

    /** 한 종목의 결과가 왔다. */
    public void show(String symbol, AiInsight insight) {
        AiInsightCard card = bySymbol.get(symbol);
        if (card == null) {
            return;
        }
        card.show(insight);
        done++;
        if (insight.anomaly().map(signal -> signal.unusual()).orElse(false)) {
            unusual++;
        }
        updateHeadline();
    }

    /** 한 종목이 실패했다. 그 자리에만 적고 나머지는 그대로 둔다. */
    public void failed(String symbol, String reason) {
        AiInsightCard card = bySymbol.get(symbol);
        if (card == null) {
            return;
        }
        card.unavailable(reason);
        done++;
        failed++;
        updateHeadline();
    }

    public void finished() {
        spinner.setVisible(false);
        spinner.setManaged(false);
        updateHeadline();
    }

    /**
     * 맨 위 한 줄.
     *
     * <p>몇 개를 봤고 그중 몇 개가 이상한지 먼저 말한다. 카드를 다 듣기 전에 이 화면에서
     * 무엇을 봐야 하는지 알 수 있어야 한다.
     */
    private void updateHeadline() {
        if (total == 0) {
            return;
        }
        StringBuilder text = new StringBuilder("AI 분석 " + done + " / " + total + "종목");
        if (done == total) {
            text.setLength(0);
            text.append("AI 분석 ").append(total).append("종목");
        }
        // 0건일 때도 말한다. 아무 말이 없으면 아직 안 센 것인지 없는 것인지 모른다.
        text.append(" · 평소와 다른 움직임 ").append(unusual).append("건");
        if (failed > 0) {
            text.append(" · 받지 못한 종목 ").append(failed).append("개");
        }
        headline.setText(text.toString());
        headline.setAccessibleText(text.toString());
    }
}
