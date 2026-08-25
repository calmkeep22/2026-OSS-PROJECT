package org.ossproject.desktop.view;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 글꼴은 CSS 이름만으로 정해지지 않는다.
 *
 * <p>실제로 그랬다. CSS 에 "Pretendard" 라고만 적어 두었더니 그 글꼴이 깔린 컴퓨터에서만
 * 나오고, 개발 컴퓨터에서는 JavaFX 기본 글꼴로 그려지고 있었다. 게다가 JavaFX 는
 * {@code -fx-font-family} 의 쉼표 목록을 웹 CSS 처럼 처리하지 않아, 대안을 늘어놓으면
 * 통째로 무시했다.
 *
 * <p>그래서 이름이 아니라 "실제로 등록되었는지" 를 검사한다.
 */
@ExtendWith(JavaFxToolkit.class)
class AppFontsTest {

    @Test
    @DisplayName("번들한 Pretendard 를 실제로 등록한다")
    void registersTheBundledFont() {
        JavaFxToolkit.onFxThread(() -> {
            String family = AppFonts.install();

            assertEquals("Pretendard", family,
                    "resources/fonts 의 Pretendard 를 읽지 못했습니다. 실제로 고른 것: " + family);
            assertTrue(Font.getFamilies().contains("Pretendard"));
        });
    }

    @Test
    @DisplayName("뿌리에 건 이름이 자식 글자에 실제로 적용된다")
    void appliesToDescendants() {
        JavaFxToolkit.onFxThread(() -> {
            String family = AppFonts.install();
            Label label = new Label("삼성전자 643,000원");
            StackPane root = new StackPane(label);
            root.setStyle(AppFonts.rootStyle(family));
            new javafx.scene.Scene(root);
            root.applyCss();
            root.layout();

            assertEquals(family, label.getFont().getFamily(),
                    "뿌리에 건 글꼴이 자식에게 내려가지 않았습니다.");
        });
    }

    /** 굵은 글씨를 따로 등록해 두지 않으면 JavaFX 가 획을 부풀려 흉내 낸다. */
    @Test
    @DisplayName("굵은 글씨도 같은 글꼴로 그린다")
    void hasARealBoldFace() {
        JavaFxToolkit.onFxThread(() -> {
            AppFonts.install();
            Font bold = Font.font("Pretendard", FontWeight.BOLD, 15);

            assertEquals("Pretendard", bold.getFamily());
            assertFalse(bold.getStyle().isBlank(), "굵기 정보가 없습니다: " + bold);
        });
    }

    /** 이름이 없으면 아무것도 걸지 않는다. 빈 style 을 걸면 기존 스타일을 지운다. */
    @Test
    @DisplayName("고를 글꼴이 없으면 뿌리에 아무것도 걸지 않는다")
    void leavesTheRootAloneWithoutAFamily() {
        assertEquals("", AppFonts.rootStyle(""));
        assertEquals("", AppFonts.rootStyle("   "));
    }
}
