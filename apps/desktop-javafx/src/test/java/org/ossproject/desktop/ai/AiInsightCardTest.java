package org.ossproject.desktop.ai;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.Confidence;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 문장이 잘리면 신뢰도와 면책이 화면에서 사라진다. 반드시 함께 전하라고 값에서까지
 * 강제해 둔 것이 마지막 한 걸음에서 없어진다.
 */
@ExtendWith(JavaFxToolkit.class)
class AiInsightCardTest {

    private static final String LONG_NARRATION =
            "삼성화재는 2026-08-21 기준 평소 범위 안에서 움직였습니다. 등락률 6.27퍼센트입니다. "
            + "이 종목의 위험도는 보통입니다. 다음 거래일(08월 24일) 크게 움직일 확률 39퍼센트, "
            + "잔잔할 확률 61퍼센트로 봅니다. 평소 하루 변동폭은 4.511퍼센트입니다.";

    @Test
    @DisplayName("긴 문장은 카드 폭 안에서 여러 줄로 접힌다")
    void wrapsLongNarrationInsideTheCard() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightCard card = new AiInsightCard(text -> { });
            card.show(AiInsight.of("000810", "삼성화재", LONG_NARRATION, Confidence.HIGH, true));

            double cardWidth = 700;
            StackPane host = new StackPane(card.root());
            new Scene(host, cardWidth, 600);
            host.applyCss();
            host.layout();

            Label narration = findNarration(card);
            assertTrue(narration.getWidth() <= cardWidth,
                    "문장이 카드보다 넓으면 잘립니다. 폭 " + narration.getWidth());
            assertTrue(narration.getHeight() > 30,
                    "여러 줄로 접혀야 합니다. 높이 " + narration.getHeight());
        });
    }

    private static Label findNarration(AiInsightCard card) {
        return (Label) ((javafx.scene.Parent) card.root()).getChildrenUnmodifiable().stream()
                .filter(node -> node instanceof Label label
                        && label.getText() != null && label.getText().startsWith("삼성화재"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("분석 문장 라벨을 찾지 못했습니다"));
    }

    /** 카드가 창보다 넓어져도 문장은 읽기 좋은 폭에서 접혀야 한다. */
    @Test
    @DisplayName("카드가 아주 넓어도 문장은 접힌다")
    void wrapsEvenWhenTheCardIsVeryWide() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightCard card = new AiInsightCard(text -> { });
            card.show(AiInsight.of("000810", "삼성화재", LONG_NARRATION, Confidence.HIGH, true));

            StackPane host = new StackPane(card.root());
            new Scene(host, 2400, 600);
            host.applyCss();
            host.layout();

            Label narration = findNarration(card);
            assertTrue(narration.getWidth() <= 900,
                    "읽기 좋은 폭을 넘으면 안 됩니다. 폭 " + narration.getWidth());
            assertTrue(narration.getHeight() > 30,
                    "여러 줄로 접혀야 합니다. 높이 " + narration.getHeight());
        });
    }

    /**
     * 세로 공간이 모자라면 부모가 라벨을 최소 높이로 누른다. 접힌 줄이 있어도 한 줄만 남고
     * 나머지가 잘린다. 실제 화면에서 이렇게 잘리고 있었다.
     */
    @Test
    @DisplayName("세로가 좁아도 접힌 줄이 눌리지 않는다")
    void keepsWrappedLinesWhenVerticalSpaceIsTight() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightCard card = new AiInsightCard(text -> { });
            card.show(AiInsight.of("000810", "삼성화재", LONG_NARRATION, Confidence.HIGH, true));

            // 카드가 요구하는 높이보다 훨씬 낮은 자리에 넣는다.
            javafx.scene.layout.VBox column = new javafx.scene.layout.VBox(card.root());
            javafx.scene.layout.VBox.setVgrow(card.root(), javafx.scene.layout.Priority.ALWAYS);
            StackPane host = new StackPane(column);
            new Scene(host, 1200, 120);
            host.applyCss();
            host.layout();

            Label narration = findNarration(card);
            assertTrue(narration.getHeight() > 30,
                    "눌려서 한 줄만 남으면 안 됩니다. 높이 " + narration.getHeight());
        });
    }
}
