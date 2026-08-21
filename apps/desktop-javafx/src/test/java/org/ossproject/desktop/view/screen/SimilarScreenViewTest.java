package org.ossproject.desktop.view.screen;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.Confidence;
import org.ossproject.ai.SimilarOutlook;
import org.ossproject.ai.SimilarStock;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이 화면이 하는 말은 하나다 — 과거 어느 구간이 지금과 모양이 닮았다. 예측이 아니다.
 * 그 구분이 화면까지 살아 오는지 본다.
 */
@ExtendWith(JavaFxToolkit.class)
class SimilarScreenViewTest {

    private static final String DISCLAIMER =
            "유사도는 형태가 닮았다는 뜻이며 미래 수익률을 의미하지 않습니다.";

    private static AiInsight insight(Optional<SimilarOutlook> outlook, SimilarStock... stocks) {
        return new AiInsight("005930", "A전자", "문안", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(stocks), outlook, Map.of());
    }

    private static SimilarScreenView view(AtomicReference<String> spoken) {
        return new SimilarScreenView("A전자",
                (text, channel) -> spoken.set(text),
                (symbol, name) -> { },
                (symbol, name) -> { },
                retry -> { });
    }

    private static List<String> textsOf(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, texts);
        return texts;
    }

    private static void collect(Node node, List<String> into) {
        if (node instanceof Label label && label.getText() != null) {
            into.add(label.getText());
        }
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            into.add(labeled.getText());
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, into));
        }
    }

    /**
     * 단서가 목록보다 먼저 와야 한다. 뒤에 두면 카드를 다 듣고 나서야 이게 예측이 아니라는
     * 것을 알게 되는데, 그때는 이미 순위와 퍼센트가 머리에 남아 있다.
     */
    @Test
    @DisplayName("단서를 목록보다 먼저 읽히는 자리에 둔다")
    void putsTheDisclaimerBeforeTheList() {
        JavaFxToolkit.onFxThread(() -> {
            SimilarScreenView view = view(new AtomicReference<>());
            Node root = view.create();
            view.show(insight(Optional.of(new SimilarOutlook(5, 3, 2, "", DISCLAIMER)),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            List<String> texts = textsOf(root);
            assertTrue(texts.indexOf(DISCLAIMER) >= 0, texts.toString());
            assertTrue(texts.indexOf(DISCLAIMER) < texts.indexOf("B종목"), texts.toString());
        });
    }

    /** 서비스가 단서를 안 주면 자리를 비우지 않는다. 단서 없는 닮은 차트가 더 위험하다. */
    @Test
    @DisplayName("서비스 단서가 없으면 우리 문장으로 대신한다")
    void fallsBackToOurOwnDisclaimer() {
        JavaFxToolkit.onFxThread(() -> {
            SimilarScreenView view = view(new AtomicReference<>());
            Node root = view.create();
            view.show(insight(Optional.empty(),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            assertTrue(textsOf(root).stream().anyMatch(t -> t.contains("미래 수익률과 무관")),
                    textsOf(root).toString());
        });
    }

    /** 닮은 구간 다음에 무슨 일이 있었는지가 유사도가 사실로 말할 수 있는 거의 전부다. */
    @Test
    @DisplayName("닮은 구간 다음의 상승·하락 건수를 보여 준다")
    void showsWhatHappenedNext() {
        JavaFxToolkit.onFxThread(() -> {
            SimilarScreenView view = view(new AtomicReference<>());
            Node root = view.create();
            view.show(insight(Optional.of(new SimilarOutlook(5, 3, 2, "", DISCLAIMER)),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            assertTrue(textsOf(root).stream().anyMatch(t -> t.contains("오른 경우 3건")),
                    textsOf(root).toString());
        });
    }

    /** 0건 0건은 사실이 아니라 자료 없음이다. 그것을 사실처럼 보여 주면 안 된다. */
    @Test
    @DisplayName("셀 것이 없으면 건수 칸을 만들지 않는다")
    void hidesTheCountsWhenThereIsNothingToCount() {
        JavaFxToolkit.onFxThread(() -> {
            SimilarScreenView view = view(new AtomicReference<>());
            Node root = view.create();
            view.show(insight(Optional.of(new SimilarOutlook(0, 0, 0, "", DISCLAIMER)),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            assertTrue(textsOf(root).stream().noneMatch(t -> t.contains("닮은 구간 다음에 있었던 일")),
                    textsOf(root).toString());
        });
    }

    /**
     * 순위와 퍼센트만 읽으면 그 숫자가 무엇에 대한 것인지 남지 않는다. 무엇과 비교한
     * 것인지 먼저 말하고, 예측이 아니라는 사실로 닫아야 한다.
     */
    @Test
    @DisplayName("같이 듣기는 비교 대상과 단서를 함께 읽어 준다")
    void spokenTextNamesTheQueryAndTheCaveat() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> spoken = new AtomicReference<>();
            SimilarScreenView view = view(spoken);
            Node root = view.create();
            view.show(insight(Optional.empty(), new SimilarStock("000660", "B종목",
                    new BigDecimal("91"), Optional.of(new BigDecimal("21")), "같은 패턴입니다.")));

            listenButtons(root).get(0).fire();

            assertTrue(spoken.get().startsWith("A전자와 닮은 차트"), spoken.get());
            assertTrue(spoken.get().contains("같은 패턴입니다."), spoken.get());
            assertTrue(spoken.get().contains("같이 움직인다는 뜻이 아닙니다"), spoken.get());
        });
    }

    /** 닮은 차트가 없는 것과 조회에 실패한 것은 다르다. 빈 화면은 전자로 읽힌다. */
    @Test
    @DisplayName("받지 못하면 이유를 적고 다시 시도할 길을 준다")
    void offersARetryWhenItFails() {
        JavaFxToolkit.onFxThread(() -> {
            SimilarScreenView view = view(new AtomicReference<>());
            Node root = view.create();
            view.unavailable("AI 서비스에 연결하지 못했습니다.");

            List<String> texts = textsOf(root);
            assertTrue(texts.contains("AI 서비스에 연결하지 못했습니다."), texts.toString());
            assertTrue(texts.contains("다시 시도"), texts.toString());
        });
    }

    private static List<javafx.scene.control.Button> listenButtons(Node root) {
        List<javafx.scene.control.Button> found = new ArrayList<>();
        collectButtons(root, found);
        found.removeIf(button -> !"같이 듣기".equals(button.getText()));
        return found;
    }

    private static void collectButtons(Node node, List<javafx.scene.control.Button> into) {
        if (node instanceof javafx.scene.control.Button button) {
            into.add(button);
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collectButtons(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectButtons(child, into));
        }
    }

    /** 버튼만 두면 눈으로 보는 사람은 카드를 눌러 보고 아무 일도 안 일어나 고장으로 읽는다. */
    @Test
    @DisplayName("카드를 눌러도 차트가 열린다")
    void opensTheChartWhenTheCardIsClicked() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> compared = new AtomicReference<>();
            SimilarScreenView view = new SimilarScreenView("A전자",
                    (text, channel) -> { }, (symbol, name) -> { },
                    (symbol, name) -> compared.set(name), retry -> { });
            Node root = view.create();
            view.show(insight(Optional.empty(),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            cardOf(root).getOnMouseClicked().handle(new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null));

            assertEquals("B종목", compared.get());
        });
    }

    /** 클릭만 받으면 키보드로 다니는 사용자에게는 이 카드가 없는 것과 같다. */
    @Test
    @DisplayName("엔터로도 차트가 열린다")
    void opensTheChartFromTheKeyboard() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> compared = new AtomicReference<>();
            SimilarScreenView view = new SimilarScreenView("A전자",
                    (text, channel) -> { }, (symbol, name) -> { },
                    (symbol, name) -> compared.set(name), retry -> { });
            Node root = view.create();
            view.show(insight(Optional.empty(),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            javafx.scene.layout.VBox card = cardOf(root);
            assertTrue(card.isFocusTraversable(), "카드가 초점을 받지 못하면 키보드로 닿을 수 없다.");
            card.getOnKeyPressed().handle(new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));

            assertEquals("B종목", compared.get());
        });
    }

    /** 관심 종목에 담으려고 눌렀는데 차트까지 열리면 안 된다. */
    @Test
    @DisplayName("카드 안의 버튼을 누르면 차트는 열지 않는다")
    void ignoresClicksThatLandOnAButton() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> compared = new AtomicReference<>();
            SimilarScreenView view = new SimilarScreenView("A전자",
                    (text, channel) -> { }, (symbol, name) -> { },
                    (symbol, name) -> compared.set(name), retry -> { });
            Node root = view.create();
            view.show(insight(Optional.empty(),
                    new SimilarStock("000660", "B종목", new BigDecimal("91"))));

            javafx.scene.layout.VBox card = cardOf(root);
            javafx.scene.control.Button watch = new javafx.scene.control.Button("관심 종목에 추가");
            javafx.scene.input.MouseEvent onButton = new javafx.scene.input.MouseEvent(
                    null, watch, javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                    javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null);
            card.getOnMouseClicked().handle(onButton);

            assertNull(compared.get());
        });
    }

    /** 닮은 종목 카드를 찾는다. 첫 카드가 1위다. */
    private static javafx.scene.layout.VBox cardOf(Node root) {
        List<javafx.scene.layout.VBox> found = new ArrayList<>();
        collectCards(root, found);
        assertFalse(found.isEmpty(), "닮은 종목 카드를 찾지 못했습니다.");
        return found.get(0);
    }

    private static void collectCards(Node node, List<javafx.scene.layout.VBox> into) {
        if (node instanceof javafx.scene.layout.VBox box
                && box.getStyleClass().contains("clickable-card")) {
            into.add(box);
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collectCards(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectCards(child, into));
        }
    }
}
