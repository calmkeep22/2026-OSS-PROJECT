package org.ossproject.desktop.ai;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.AiInsight;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * AI 분석 결과를 보여 주는 칸.
 *
 * <p>분석 문장은 서비스가 스크린리더용으로 쓴 것을 그대로 쓴다. 화면이 문장을 새로 지어
 * 내면 숫자를 앞에 두게 되고, 신뢰도가 낮다는 사실이 문장에서 빠진다.
 *
 * <p>함께 전해야 하는 단서는 고를 수 있는 것이 아니라 값이 정해 준다. 전부 적는다.
 */
public final class AiInsightCard {

    /** 한 줄이 이보다 길면 눈이 다음 줄 첫머리를 놓친다. */
    private static final double MAX_READING_WIDTH = 820;

    private final Label narration = new Label();
    private final Label direction = new Label();
    private final Label risk = new Label();
    private final Label similar = new Label();
    private final Label caveats = new Label();
    private final Button listen;
    private final Button retry;
    private final VBox root;

    public AiInsightCard(Consumer<String> onListen) {
        Objects.requireNonNull(onListen, "onListen");
        narration.setWrapText(true);
        narration.getStyleClass().add("ai-narration");
        // 방향 예측은 문안과 섞지 않는다. 문안은 변동성 이야기라 한 덩이로 읽으면 어느
        // 확률이 무엇에 대한 것인지 흐려진다.
        direction.setWrapText(true);
        direction.getStyleClass().add("ai-narration");
        direction.setVisible(false);
        direction.setManaged(false);
        // 위험도와 닮은 차트도 문안과 섞지 않는다. 한 덩이가 되면 어느 문장이 사실이고
        // 어느 문장이 참고인지 귀로 가려내기 어렵다.
        for (Label label : new Label[]{risk, similar}) {
            label.setWrapText(true);
            label.getStyleClass().add("ai-narration");
            label.setVisible(false);
            label.setManaged(false);
        }
        caveats.setWrapText(true);
        caveats.getStyleClass().add("safety-note");
        caveats.setVisible(false);
        caveats.setManaged(false);

        listen = new Button("AI 분석 듣기");
        listen.setDisable(true);
        listen.setOnAction(event -> onListen.accept(spoken));

        retry = new Button("다시 시도");
        retry.setVisible(false);
        retry.setManaged(false);

        Label title = new Label("AI 분석");
        title.getStyleClass().add("card-title");
        root = new VBox(10, title, narration, direction, risk, similar, caveats,
                new javafx.scene.layout.HBox(8, listen, retry));
        root.getStyleClass().add("panel-card");
        root.setPadding(new Insets(16));
        // 부모가 폭을 잡아 주지 않으면 wrapText 만으로는 줄이 바뀌지 않는다. 라벨이 한 줄로
        // 늘어나 문장 뒤쪽이 잘린다. 잘린 문장은 신뢰도와 면책이 사라진 문장이다.
        bindWrapWidth(narration);
        bindWrapWidth(direction);
        bindWrapWidth(risk);
        bindWrapWidth(similar);
        bindWrapWidth(caveats);
        waiting();
    }

    /**
     * 읽기 좋은 폭에서 줄을 바꾼다.
     *
     * <p>카드 폭에만 맞추면 카드가 창보다 넓어졌을 때 문장이 창 밖으로 나가 잘린다. 한 줄이
     * 너무 길어도 눈이 다음 줄 첫머리를 찾기 어렵다. 둘 중 좁은 쪽을 쓴다.
     */
    private void bindWrapWidth(Label label) {
        label.setMinWidth(0);
        label.maxWidthProperty().bind(javafx.beans.binding.Bindings.min(
                root.widthProperty().subtract(32), MAX_READING_WIDTH));
        // 세로 공간이 모자라면 부모가 라벨을 최소 높이로 누른다. 접힌 줄이 있어도 한 줄만
        // 남고 나머지가 잘린다. 접은 높이를 최소로 삼아 눌리지 않게 한다.
        label.setMinHeight(Region.USE_PREF_SIZE);
    }

    private String spoken = "";

    public javafx.scene.Node root() {
        return root;
    }

    public void waiting() {
        show("AI 분석을 기다리고 있습니다.", "");
        listen.setDisable(true);
        hideRetry();
    }

    /**
     * 서버가 아직 준비 중이다.
     *
     * <p>분석 서버는 모델을 읽고 지수를 받은 뒤에야 포트를 연다. 그동안은 연결이 거부되는데
     * 그것을 실패로 적으면 사용자는 고칠 수 없는 문제로 읽고 포기한다.
     */
    public void starting(Runnable onRetry) {
        show("AI 서버를 준비하고 있습니다. 10초쯤 걸립니다.", "");
        listen.setDisable(true);
        showRetry(onRetry);
    }

    /**
     * 분석 결과를 보여 준다.
     *
     * <p>문안과 단서를 함께 둔다. 읽어 주는 문장도 같은 것을 쓴다. 화면 글자와 음성이
     * 다르면 스크린리더 사용자가 다른 내용을 듣는다.
     */
    public void show(AiInsight insight) {
        hideRetry();
        String caveatText = String.join(" ", insight.requiredCaveats());
        String failureText = insight.partialFailureText().orElse("");
        show(insight.narration(), (caveatText + " " + failureText).trim());
        setLine(direction, insight.directionText().orElse(""));
        setLine(risk, insight.riskText().orElse(""));
        setLine(similar, insight.similarText().orElse(""));
        spoken = insight.fullNarration();
        listen.setDisable(false);
    }

    /**
     * 분석을 받지 못한 이유를 적는다.
     *
     * <p>빈 칸으로 두지 않는다. 분석이 없는 것과 "이상 없음" 은 다른 뜻인데, 아무것도
     * 없으면 사용자는 그 종목에 문제가 없다고 읽는다.
     */
    public void unavailable(String reason) {
        show(reason == null || reason.isBlank() ? "AI 분석을 사용할 수 없습니다." : reason, "");
        listen.setDisable(true);
        hideRetry();
    }

    /** 다시 시도할 수단을 함께 준다. 실패만 알리고 길을 주지 않으면 막다른 길이 된다. */
    public void unavailable(String reason, Runnable onRetry) {
        unavailable(reason);
        showRetry(onRetry);
    }

    private void showRetry(Runnable onRetry) {
        retry.setOnAction(event -> onRetry.run());
        retry.setVisible(true);
        retry.setManaged(true);
    }

    private void hideRetry() {
        retry.setVisible(false);
        retry.setManaged(false);
    }

    /** 값이 없으면 줄 자체를 없앤다. 빈 줄은 스크린리더가 지나가며 읽을 것이 없다. */
    private static void setLine(Label label, String text) {
        boolean has = !text.isBlank();
        label.setText(text);
        label.setAccessibleText(text);
        label.setVisible(has);
        label.setManaged(has);
    }

    private void show(String main, String extra) {
        setLine(direction, "");
        setLine(risk, "");
        setLine(similar, "");
        narration.setText(main);
        narration.setAccessibleText(main);
        spoken = main;
        if (extra.isBlank()) {
            caveats.setVisible(false);
            caveats.setManaged(false);
            return;
        }
        caveats.setText(extra);
        caveats.setAccessibleText(extra);
        caveats.setVisible(true);
        caveats.setManaged(true);
    }
}
