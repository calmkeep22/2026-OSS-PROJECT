package org.ossproject.desktop.view.screen;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(JavaFxToolkit.class)
class ChatScreenViewTest {
    @Test
    @DisplayName("답하지 않은 것도 독립 챗봇 화면의 답 자리에 보여 준다")
    void showsDeclinedAnswersInTheSamePlace() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Consumer<ChatAnswer>> sink = new AtomicReference<>();
            ChatScreenView view = new ChatScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> sink.set(onAnswer));
            Node root = view.create();

            // 고른 질문에도 거절이 돌아올 수 있다. 근거가 없거나 답하지 않기로 한
            // 것이면 그렇다. 거절도 답과 같은 자리에 남아야 한다.
            askThrough(root, "얼마나 움직일지 예측해줘");
            sink.get().accept(new ChatAnswer("사고파는 판단은 알려 드리지 않습니다.",
                    List.of(), true, List.of("핵심 수치 알려줘")));

            List<String> texts = textsOf(root);
            assertTrue(texts.contains("사고파는 판단은 알려 드리지 않습니다."), texts.toString());
            assertTrue(texts.contains("답변 듣기"), texts.toString());
        });
    }

    @Test
    @DisplayName("근거가 있으면 독립 챗봇 화면에 함께 적는다")
    void showsGroundsOnlyWhenThereAreAny() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Consumer<ChatAnswer>> sink = new AtomicReference<>();
            ChatScreenView view = new ChatScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> sink.set(onAnswer));
            Node root = view.create();

            askThrough(root, "핵심 수치 알려줘");
            sink.get().accept(new ChatAnswer("확률 52퍼센트입니다.",
                    List.of("변동성 예측 모델"), false, List.of()));

            assertTrue(textsOf(root).contains("근거: 변동성 예측 모델."), textsOf(root).toString());
        });
    }

    @Test
    @DisplayName("답변 범위와 투자 추천 금지를 늘 표시한다")
    void alwaysCarriesScopeAndSafetyText() {
        JavaFxToolkit.onFxThread(() -> {
            Node root = new ChatScreenView((a, b) -> { },
                    (question, onAnswer) -> { }).create();

            List<String> texts = textsOf(root);
            assertTrue(texts.contains("AI 챗봇"), texts.toString());
            assertTrue(texts.stream().anyMatch(text ->
                            text.startsWith("검증된 AI 분석과 수집된 뉴스만 근거로 답합니다.")),
                    texts.toString());
            assertTrue(texts.contains("투자 추천 · 매수·매도 판단 · 가격 예측은 제공하지 않습니다."));
            assertFalse(texts.stream().anyMatch(text -> text.contains("A전자 AI 챗봇")));
        });
    }

    /**
     * 뒤쪽은 낱말로 의도를 가르는 분기라 답할 수 있는 것이 정해져 있다. 자유 입력을
     * 두면 대부분 "이해하지 못했습니다" 로 끝나므로, 물어볼 것을 늘어놓고 고르게 한다.
     */
    @Test
    @DisplayName("자유 입력 대신 고를 질문만 둔다")
    void offersAFixedSetOfQuestionsInsteadOfFreeText() {
        JavaFxToolkit.onFxThread(() -> {
            Node root = new ChatScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> { }).create();

            assertNull(findField(root), "자유 입력 칸이 남아 있으면 답 못 할 질문을 받게 됩니다.");
            assertTrue(buttons(root, "보내기").isEmpty(), "보낼 것이 없으므로 보내기도 없습니다.");

            List<String> texts = textsOf(root);
            for (String askable : List.of("쉽게 설명해줘", "핵심 수치 알려줘", "무슨 일이 있었어?",
                    "위험도가 어때?", "얼마나 움직일지 예측해줘")) {
                assertTrue(texts.contains(askable), askable + " 질문이 없습니다. " + texts);
            }
        });
    }

    @Test
    @DisplayName("Control+Enter로 현재 고른 질문을 보낸다")
    void asksTheFocusedQuestionWithControlEnter() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> asked = new AtomicReference<>();
            Node root = new ChatScreenView((a, b) -> { },
                    (question, onAnswer) -> asked.set(question)).create();

            root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, true, false, false));

            assertEquals("쉽게 설명해줘", asked.get());
        });
    }

    /**
     * 목록이 답마다 바뀌면, 방금 있던 자리에 다른 것이 놓인다. 소리로 훑는 사용자는
     * 그때마다 처음부터 다시 읽어야 한다.
     */
    @Test
    @DisplayName("답을 받아도 질문 목록은 그대로다")
    void keepsTheQuestionListStableAcrossAnswers() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Consumer<ChatAnswer>> sink = new AtomicReference<>();
            Node root = new ChatScreenView((a, b) -> { },
                    (question, onAnswer) -> sink.set(onAnswer)).create();

            List<String> before = chipTexts(root);
            askThrough(root, "핵심 수치 알려줘");
            sink.get().accept(new ChatAnswer("수치입니다.", List.of(), false,
                    List.of("위험도가 어때?")));

            assertEquals(before, chipTexts(root), "답에 딸려 온 추천으로 목록이 바뀌었습니다.");
        });
    }

    /** 종목이 바뀌면 기록에 그 자리를 남긴다. 안 남기면 위쪽 답이 어느 종목 것인지 모른다. */
    @Test
    @DisplayName("종목을 바꾸면 기록에 경계를 남긴다")
    void marksWhereTheStockChanged() {
        JavaFxToolkit.onFxThread(() -> {
            ChatScreenView view = new ChatScreenView((a, b) -> { }, (question, onAnswer) -> { });
            Node root = view.create();

            view.noteStock("삼성전자");

            assertTrue(textsOf(root).stream().anyMatch(text -> text.contains("삼성전자")),
                    "종목이 바뀐 자리가 기록에 없습니다.");
        });
    }

    /**
     * 칩마다 탭을 세우면 마지막 질문까지 다섯 번을 눌러야 한다. 묶음 전체가 탭 한 번이고
     * 그 안은 화살표로 옮긴다 — 툴바와 라디오 묶음이 쓰는 방식이다.
     */
    @Test
    @DisplayName("질문 묶음은 탭 한 번이고 안쪽은 화살표로 옮긴다")
    void keepsTheQuestionGroupToOneTabStop() {
        JavaFxToolkit.onFxThread(() -> {
            ChatScreenView view = new ChatScreenView((a, b) -> { }, (question, onAnswer) -> { });
            Node root = view.create();
            new javafx.scene.Scene(new javafx.scene.layout.StackPane(root));

            List<Button> chips = new ArrayList<>();
            collectButtons(root, chips);
            chips.removeIf(button -> !button.getStyleClass().contains("chat-suggestion-chip"));

            assertEquals(1, chips.stream().filter(Node::isFocusTraversable).count(),
                    "탭으로 들어오는 자리는 하나여야 합니다.");
            assertTrue(chips.get(0).isFocusTraversable(), "첫 질문이 그 자리여야 합니다.");
        });
    }

    /** 화살표로 옮겨도 무엇 위에 서 있는지 모르면 소용이 없다. */
    @Test
    @DisplayName("초점이 닿은 질문을 읽어 준다")
    void speaksTheFocusedQuestion() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> spoken = new ArrayList<>();
            ChatScreenView view = new ChatScreenView((a, b) -> { }, (question, onAnswer) -> { });
            view.setFocusSpeaker(spoken::add);
            Node root = view.create();
            javafx.scene.Scene scene = new javafx.scene.Scene(
                    new javafx.scene.layout.StackPane(root), 1200, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            view.focusQuestions();

            assertFalse(spoken.isEmpty(), "초점이 닿은 질문을 읽어 주지 않았습니다.");
            assertTrue(spoken.get(0).startsWith("쉽게 설명해줘"), spoken.toString());
            assertTrue(spoken.get(0).contains("5가지 중 1번째"), spoken.toString());
        });
    }

    /** 들을 것이 없을 때 단축키가 무엇을 할지는 부르는 쪽이 정한다. 여기서는 없다고만 답한다. */
    @Test
    @DisplayName("답을 받기 전에는 들을 것이 없다고 답한다")
    void reportsNothingToListenToBeforeAnyAnswer() {
        JavaFxToolkit.onFxThread(() -> {
            ChatScreenView view = new ChatScreenView((a, b) -> { }, (question, onAnswer) -> { });
            view.create();

            assertFalse(view.hasAnswer());
        });
    }

    private static List<String> chipTexts(Node root) {
        List<Button> all = new ArrayList<>();
        collectButtons(root, all);
        List<String> texts = new ArrayList<>();
        for (Button button : all) {
            if (button.getStyleClass().contains("chat-suggestion-chip")) texts.add(button.getText());
        }
        return texts;
    }

    @Test
    @DisplayName("질문 목록은 대화 스크롤 밖의 화면 하단에 고정한다")
    void keepsTheComposerOutsideTheMessageScroll() {
        JavaFxToolkit.onFxThread(() -> {
            Node root = new ChatScreenView((a, b) -> { },
                    (question, onAnswer) -> { }).create();

            assertTrue(root instanceof BorderPane);
            BorderPane page = (BorderPane) root;
            assertTrue(page.getCenter() instanceof ScrollPane);
            assertNotNull(page.getBottom());
        });
    }

    @Test
    @DisplayName("답변별 버튼과 상단 버튼으로 챗봇 답변을 읽는다")
    void readsAnAnswerFromBothListenButtons() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Consumer<ChatAnswer>> sink = new AtomicReference<>();
            AtomicReference<String> spoken = new AtomicReference<>();
            ChatScreenView view = new ChatScreenView((text, id) -> spoken.set(text),
                    (question, onAnswer) -> sink.set(onAnswer));
            Node root = view.create();

            askThrough(root, "쉽게 설명해줘");
            sink.get().accept(new ChatAnswer("쉽게 풀어 쓴 답변입니다.",
                    List.of(), false, List.of()));

            buttons(root, "답변 듣기").get(0).fire();
            assertEquals("쉽게 풀어 쓴 답변입니다.", spoken.get());
            spoken.set(null);
            buttons(root, "최근 답변 듣기 (Alt+L)").get(0).fire();
            assertEquals("쉽게 풀어 쓴 답변입니다.", spoken.get());
            // 단축키도 같은 길로 간다. 따로 읽으면 나중에 한쪽만 고쳐져 어긋난다.
            spoken.set(null);
            assertTrue(view.hasAnswer());
            view.listenToLatestAnswer();
            assertEquals("쉽게 풀어 쓴 답변입니다.", spoken.get());
        });
    }

    /** 이제 묻는 길은 하나뿐이다 — 늘어놓은 질문 중 하나를 누른다. */
    private static void askThrough(Node root, String question) {
        List<Button> chips = buttons(root, question);
        assertFalse(chips.isEmpty(), "\"" + question + "\" 질문 단추를 찾지 못했습니다.");
        chips.get(0).fire();
    }

    private static TextField findField(Node node) {
        if (node instanceof TextField field) return field;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            return findField(scroll.getContent());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TextField found = findField(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<String> textsOf(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, texts);
        return texts;
    }

    private static void collect(Node node, List<String> into) {
        if (node instanceof Labeled labeled && labeled.getText() != null) into.add(labeled.getText());
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, into));
        }
    }

    private static List<Button> buttons(Node root, String text) {
        List<Button> found = new ArrayList<>();
        collectButtons(root, found);
        found.removeIf(button -> !text.equals(button.getText()));
        return found;
    }

    private static void collectButtons(Node node, List<Button> into) {
        if (node instanceof Button button) into.add(button);
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collectButtons(scroll.getContent(), into);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectButtons(child, into));
        }
    }
}
