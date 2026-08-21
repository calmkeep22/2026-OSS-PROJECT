package org.ossproject.desktop.view.screen;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.ai.ChatAnswer;
import org.ossproject.ai.NewsArticle;
import org.ossproject.ai.NewsDigest;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 감성 지수는 여론의 방향을 요약한 값이지 주가 예측이 아니다. 점수만 보이면 사용자는
 * 그것을 신호로 읽는다. 그 구분이 화면까지 살아 오는지 본다.
 */
@ExtendWith(JavaFxToolkit.class)
class NewsScreenViewTest {

    private static NewsDigest digest() {
        return new NewsDigest("005930", "A전자", Optional.of(12.4), "약간 긍정",
                4, 3, 2,
                List.of("A전자가 신규 시설 투자를 공시했습니다."),
                Optional.of("오늘 시황 보도입니다."),
                List.of(new NewsArticle("A전자, 신규 시설 투자 계획 공시", "경제 신문",
                        Instant.parse("2026-08-22T02:02:00Z"), "https://example.test/1",
                        Optional.of("positive"))),
                "A전자 뉴스 브리핑입니다. 주가 예측이 아닙니다.");
    }

    private static NewsDigest emptyDigest() {
        return new NewsDigest("005930", "A전자", Optional.empty(), "", 0, 0, 0,
                List.of(), Optional.empty(), List.of(), "관련 뉴스를 찾지 못했습니다.");
    }

    private static List<String> textsOf(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, texts);
        return texts;
    }

    private static void collect(Node node, List<String> into) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            into.add(labeled.getText());
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collect(scroll.getContent(), into);
        } else if (node instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                into.add(tab.getText());
                if (tab.getContent() != null) {
                    collect(tab.getContent(), into);
                }
            }
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
        if (node instanceof Button button) {
            into.add(button);
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            collectButtons(scroll.getContent(), into);
        } else if (node instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                if (tab.getContent() != null) {
                    collectButtons(tab.getContent(), into);
                }
            }
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectButtons(child, into));
        }
    }

    @Test
    @DisplayName("감성 지수를 예측이 아니라는 사실과 함께 보여 준다")
    void alwaysSaysTheScoreIsNotAForecast() {
        JavaFxToolkit.onFxThread(() -> {
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> { }, () -> { });
            Node root = view.create();
            view.show(digest());

            assertTrue(textsOf(root).stream().anyMatch(t -> t.contains("주가 예측이 아닙니다")),
                    textsOf(root).toString());
        });
    }

    /** 뉴스가 없는 것과 받지 못한 것은 다르다. 둘 다 빈 목록이면 구별되지 않는다. */
    @Test
    @DisplayName("기사가 없으면 없다고 적는다")
    void saysSoWhenThereIsNoNews() {
        JavaFxToolkit.onFxThread(() -> {
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> { }, () -> { });
            Node root = view.create();
            view.show(emptyDigest());

            assertTrue(textsOf(root).contains("관련 뉴스를 찾지 못했습니다."), textsOf(root).toString());
        });
    }

    @Test
    @DisplayName("받지 못하면 이유를 적고 다시 시도할 길을 준다")
    void offersARetryWhenItFails() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Boolean> reloaded = new AtomicReference<>(false);
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> { }, () -> reloaded.set(true));
            Node root = view.create();
            view.unavailable("뉴스를 받지 못했습니다.");

            buttons(root, "다시 시도").get(0).fire();
            assertTrue(reloaded.get());
        });
    }

    /**
     * 답하지 않기로 한 것은 실패가 아니다. 오류처럼 보여 주면 사용자는 다시 물으면 답이
     * 나올 것으로 오해한다.
     */
    @Test
    @DisplayName("답하지 않은 것도 답 자리에 그대로 보여 준다")
    void showsDeclinedAnswersInTheSamePlace() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Consumer<ChatAnswer>> sink = new AtomicReference<>();
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> sink.set(onAnswer), () -> { });
            Node root = view.create();

            askThrough(root, "지금 사도 돼?");
            sink.get().accept(new ChatAnswer("사고파는 판단은 알려 드리지 않습니다.",
                    List.of(), true, List.of("핵심 수치 알려줘")));

            List<String> texts = textsOf(root);
            assertTrue(texts.contains("사고파는 판단은 알려 드리지 않습니다."), texts.toString());
            assertTrue(texts.contains("답변 듣기"), texts.toString());
        });
    }

    /** 근거 없이 답한 것과 근거가 있는 답을 화면에서 구별할 수 있어야 한다. */
    @Test
    @DisplayName("근거가 있으면 함께 적고 없으면 적지 않는다")
    void showsGroundsOnlyWhenThereAreAny() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Consumer<ChatAnswer>> sink = new AtomicReference<>();
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> sink.set(onAnswer), () -> { });
            Node root = view.create();

            askThrough(root, "핵심 수치 알려줘");
            sink.get().accept(new ChatAnswer("확률 52퍼센트입니다.",
                    List.of("변동성 예측 모델"), false, List.of()));

            assertTrue(textsOf(root).contains("근거: 변동성 예측 모델."), textsOf(root).toString());
        });
    }

    @Test
    @DisplayName("투자 추천을 하지 않는다는 사실을 챗봇 화면에 늘 적어 둔다")
    void alwaysCarriesTheChatFooter() {
        JavaFxToolkit.onFxThread(() -> {
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> { }, () -> { });
            Node root = view.create();

            assertTrue(textsOf(root).contains("투자 추천 · 가격 예측은 제공하지 않습니다."),
                    textsOf(root).toString());
        });
    }

    /** 빈 질문을 보내면 대화 기록만 지저분해지고 서버는 거절만 돌려준다. */
    @Test
    @DisplayName("빈 질문은 보내지 않는다")
    void ignoresAnEmptyQuestion() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> asked = new AtomicReference<>();
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { },
                    (question, onAnswer) -> asked.set(question), () -> { });
            Node root = view.create();

            buttons(root, "보내기").get(0).fire();
            assertNull(asked.get());
        });
    }

    private static void askThrough(Node root, String question) {
        javafx.scene.control.TextField field = findField(root);
        assertNotNull(field, "질문 입력 칸을 찾지 못했습니다.");
        field.setText(question);
        buttons(root, "보내기").get(0).fire();
    }

    private static javafx.scene.control.TextField findField(Node node) {
        if (node instanceof javafx.scene.control.TextField field) {
            return field;
        }
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            return findField(scroll.getContent());
        }
        if (node instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                if (tab.getContent() != null) {
                    javafx.scene.control.TextField found = findField(tab.getContent());
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.TextField found = findField(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
