package org.ossproject.desktop.ai;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AnomalySignal;
import org.ossproject.ai.Confidence;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이상 감지 화면은 종목 하나를 들여다보는 곳이 아니라 여러 종목을 훑는 곳이다. 그리고
 * 그 화면에는 종목 선택기가 없어서, 어느 종목 이야기인지가 카드에 적혀 있어야 한다.
 */
@ExtendWith(JavaFxToolkit.class)
class AiInsightListPanelTest {

    private static AiInsight insight(String symbol, String name, boolean unusual) {
        AnomalySignal signal = new AnomalySignal(unusual, unusual ? "강함" : "정상", "상승",
                LocalDate.of(2026, 8, 21), new BigDecimal("1.2"), "", "");
        return new AiInsight(symbol, name, name + " 문안입니다.", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.of(signal),
                List.of(), Optional.empty(), Map.of());
    }

    private static List<String> textsOf(Node node) {
        List<String> texts = new ArrayList<>();
        collect(node, texts);
        return texts;
    }

    private static void collect(Node node, List<String> into) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            into.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, into));
        }
    }

    /** 카드마다 "AI 분석" 이라고만 적혀 있으면 위에서 아래로 듣는 사용자는 길을 잃는다. */
    @Test
    @DisplayName("카드마다 종목명을 제목으로 단다")
    void namesEveryCard() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.starting(List.of("005930", "000660"), List.of("A전자", "B반도체"));

            List<String> texts = textsOf(panel.root());
            assertTrue(texts.contains("A전자"), texts.toString());
            assertTrue(texts.contains("B반도체"), texts.toString());
        });
    }

    /**
     * 결과가 오는 대로 카드를 붙이면 목록이 위아래로 흔들린다. 스무 종목이면 3초 동안
     * 그 흔들림이 이어지고, 스크린리더는 그때마다 처음부터 다시 읽는다.
     */
    @Test
    @DisplayName("결과가 늦게 와도 카드 자리는 처음 순서를 지킨다")
    void keepsTheOriginalOrderWhateverArrivesFirst() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.starting(List.of("005930", "000660"), List.of("A전자", "B반도체"));

            // 두 번째 종목이 먼저 도착한다.
            panel.show("000660", insight("000660", "B반도체", false));
            panel.show("005930", insight("005930", "A전자", false));

            List<String> texts = textsOf(panel.root());
            assertTrue(texts.indexOf("A전자") < texts.indexOf("B반도체"), texts.toString());
        });
    }

    /** 카드를 다 듣기 전에 이 화면에서 무엇을 봐야 하는지 알 수 있어야 한다. */
    @Test
    @DisplayName("맨 위에 이상한 종목이 몇 건인지 센다")
    void countsTheUnusualOnesUpTop() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.starting(List.of("005930", "000660"), List.of("A전자", "B반도체"));
            panel.show("005930", insight("005930", "A전자", true));
            panel.show("000660", insight("000660", "B반도체", false));
            panel.finished();

            assertTrue(textsOf(panel.root()).stream()
                    .anyMatch(t -> t.contains("평소와 다른 움직임 1건")), textsOf(panel.root()).toString());
        });
    }

    /** 0건일 때도 말한다. 아무 말이 없으면 아직 안 센 것인지 없는 것인지 모른다. */
    @Test
    @DisplayName("이상한 종목이 없어도 없다고 말한다")
    void saysZeroOutLoud() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.starting(List.of("005930"), List.of("A전자"));
            panel.show("005930", insight("005930", "A전자", false));
            panel.finished();

            assertTrue(textsOf(panel.root()).stream()
                    .anyMatch(t -> t.contains("평소와 다른 움직임 0건")), textsOf(panel.root()).toString());
        });
    }

    /** 하나 때문에 전부 못 보면 이 화면은 쓸 수 없다. */
    @Test
    @DisplayName("한 종목이 실패해도 나머지는 그대로 보여 준다")
    void oneFailureDoesNotSinkTheList() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.starting(List.of("005930", "000660"), List.of("A전자", "B반도체"));
            panel.failed("005930", "시세가 부족합니다.");
            panel.show("000660", insight("000660", "B반도체", false));
            panel.finished();

            List<String> texts = textsOf(panel.root());
            assertTrue(texts.contains("시세가 부족합니다."), texts.toString());
            assertTrue(texts.contains("B반도체 문안입니다."), texts.toString());
            assertTrue(texts.stream().anyMatch(t -> t.contains("받지 못한 종목 1개")), texts.toString());
        });
    }

    /** 빈 칸으로 두면 분석이 실패한 것처럼 보인다. */
    @Test
    @DisplayName("감시할 종목이 없으면 왜 비었는지 적는다")
    void explainsAnEmptyList() {
        JavaFxToolkit.onFxThread(() -> {
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.empty("보유 종목이나 관심 종목을 추가하면 AI 분석을 함께 보여 드립니다.");

            assertTrue(textsOf(panel.root())
                    .contains("보유 종목이나 관심 종목을 추가하면 AI 분석을 함께 보여 드립니다."));
        });
    }

    /** 기능을 감추지 않고 이유를 적는다. 아무것도 없으면 원래 없는 기능으로 읽는다. */
    @Test
    @DisplayName("쓸 수 없으면 이유와 다시 시도할 길을 준다")
    void offersARetryWhenUnavailable() {
        JavaFxToolkit.onFxThread(() -> {
            AtomicReference<Boolean> retried = new AtomicReference<>(false);
            AiInsightListPanel panel = new AiInsightListPanel(text -> { });
            panel.unavailable("AI 서버를 준비하고 있습니다.", () -> retried.set(true));

            List<String> texts = textsOf(panel.root());
            assertTrue(texts.contains("AI 서버를 준비하고 있습니다."), texts.toString());
            assertTrue(texts.contains("다시 시도"), texts.toString());
        });
    }
}
