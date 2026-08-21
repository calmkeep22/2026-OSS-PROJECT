package org.ossproject.desktop.ai;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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

    private final Label narration = new Label();
    private final Label caveats = new Label();
    private final Button listen;
    private final VBox root;

    public AiInsightCard(Consumer<String> onListen) {
        Objects.requireNonNull(onListen, "onListen");
        narration.setWrapText(true);
        narration.getStyleClass().add("ai-narration");
        caveats.setWrapText(true);
        caveats.getStyleClass().add("safety-note");
        caveats.setVisible(false);
        caveats.setManaged(false);

        listen = new Button("AI 분석 듣기");
        listen.setDisable(true);
        listen.setOnAction(event -> onListen.accept(spoken));

        Label title = new Label("AI 분석");
        title.getStyleClass().add("card-title");
        root = new VBox(10, title, narration, caveats, listen);
        root.getStyleClass().add("panel-card");
        root.setPadding(new Insets(16));
        waiting();
    }

    private String spoken = "";

    public javafx.scene.Node root() {
        return root;
    }

    public void waiting() {
        show("AI 분석을 기다리고 있습니다.", "");
        listen.setDisable(true);
    }

    /**
     * 분석 결과를 보여 준다.
     *
     * <p>문안과 단서를 함께 둔다. 읽어 주는 문장도 같은 것을 쓴다. 화면 글자와 음성이
     * 다르면 스크린리더 사용자가 다른 내용을 듣는다.
     */
    public void show(AiInsight insight) {
        String caveatText = String.join(" ", insight.requiredCaveats());
        String failureText = insight.partialFailureText().orElse("");
        show(insight.narration(), (caveatText + " " + failureText).trim());
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
    }

    private void show(String main, String extra) {
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
