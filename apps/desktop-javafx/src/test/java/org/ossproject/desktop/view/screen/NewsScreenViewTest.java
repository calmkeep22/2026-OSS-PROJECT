package org.ossproject.desktop.view.screen;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.ai.NewsArticle;
import org.ossproject.ai.NewsDigest;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectButtons(child, into));
        }
    }

    @Test
    @DisplayName("감성 지수를 예측이 아니라는 사실과 함께 보여 준다")
    void alwaysSaysTheScoreIsNotAForecast() {
        JavaFxToolkit.onFxThread(() -> {
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { }, () -> { });
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
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { }, () -> { });
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
                    () -> reloaded.set(true));
            Node root = view.create();
            view.unavailable("뉴스를 받지 못했습니다.");

            buttons(root, "다시 시도").get(0).fire();
            assertTrue(reloaded.get());
        });
    }

    /**
     * 칸을 조용히 빼지 않는다. 사건 칸이 아예 없는 것과 사건이 없는 것은 다른 뜻인데,
     * 없어져 버리면 사용자는 화면이 덜 그려진 것인지 사건이 없는 것인지 알 수 없다.
     */
    @Test
    @DisplayName("사건이 없어도 칸은 남기고 없다고 적는다")
    void keepsTheEventSectionEvenWhenEmpty() {
        JavaFxToolkit.onFxThread(() -> {
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { }, () -> { });
            Node root = view.create();
            view.show(emptyDigest());

            List<String> texts = textsOf(root);
            assertTrue(texts.contains("주요 사건"), texts.toString());
            assertTrue(texts.contains("묶어 낼 만한 사건을 찾지 못했습니다."), texts.toString());
            assertTrue(texts.contains("기사"), texts.toString());
        });
    }

    @Test
    @DisplayName("사건과 기사를 받으면 둘 다 목록으로 보여 준다")
    void showsEveryEventAndArticle() {
        JavaFxToolkit.onFxThread(() -> {
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { }, () -> { });
            Node root = view.create();
            view.show(digest());

            List<String> texts = textsOf(root);
            assertTrue(texts.stream().anyMatch(t -> t.contains("1. A전자가 신규 시설 투자를")),
                    texts.toString());
            assertTrue(texts.contains("A전자, 신규 시설 투자 계획 공시"), texts.toString());
            assertTrue(texts.stream().anyMatch(t -> t.contains("오늘 시황 보도입니다.")),
                    texts.toString());
        });
    }

    @Test
    @DisplayName("기사 원문 버튼으로 받은 언론사 주소를 연다")
    void opensTheOriginalArticleInTheBrowser() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> opened = new AtomicReference<>();
            NewsScreenView view = new NewsScreenView("A전자", (a, b) -> { }, () -> { },
                    opened::set);
            Node root = view.create();
            view.show(digest());

            Button original = buttons(root, "원문 열기").get(0);
            assertTrue(original.getAccessibleText().contains("기본 브라우저"));
            original.fire();
            assertEquals("https://example.test/1", opened.get());
        });
    }

    @Test
    @DisplayName("기사 카드 키보드와 새로고침 단축키가 같은 동작을 실행한다")
    void supportsArticleAndReloadShortcuts() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<String> spoken = new AtomicReference<>();
            AtomicReference<String> opened = new AtomicReference<>();
            AtomicReference<Boolean> reloaded = new AtomicReference<>(false);
            NewsScreenView view = new NewsScreenView("A전자", (text, id) -> spoken.set(text),
                    () -> reloaded.set(true), opened::set);
            Node root = view.create();
            view.show(digest());

            VBox article = articleCard(root);
            assertNotNull(article, "키보드로 탐색할 기사 카드를 찾지 못했습니다.");
            article.fireEvent(key(KeyCode.ENTER, false));
            assertEquals("https://example.test/1", opened.get());
            article.fireEvent(key(KeyCode.SPACE, false));
            assertTrue(spoken.get().contains("신규 시설 투자"), spoken.get());

            root.fireEvent(key(KeyCode.R, true));
            assertTrue(reloaded.get());
        });
    }

    private static VBox articleCard(Node node) {
        if (node instanceof VBox box && box.getAccessibleHelp() != null
                && box.getAccessibleHelp().contains("원문 열기")) return box;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            VBox found = articleCard(scroll.getContent());
            if (found != null) return found;
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                VBox found = articleCard(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static KeyEvent key(KeyCode code, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, false, false);
    }
}
