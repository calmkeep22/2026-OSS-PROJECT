package org.ossproject.desktop.navigation;

import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 잘못된 SVG 경로는 아무도 알려 주지 않는다.
 *
 * <p>컴파일러는 문자열만 보고, JavaFX 는 예외 없이 빈 모양을 그린다. 실제로 주문 아이콘의
 * 호 명령이 인자를 여덟 개 받고 있었고 — 일곱 개여야 한다 — 사이드바에 빈 칸이 하나
 * 있었다. 누르면 눌리기는 해서 더 알아채기 어려웠다.
 *
 * <p>그려 보고 크기를 재는 것 말고는 확인할 방법이 없다.
 */
@ExtendWith(JavaFxToolkit.class)
class NavigationIconsTest {

    @ParameterizedTest
    @EnumSource(Screen.class)
    @DisplayName("모든 화면 아이콘이 실제로 그려진다")
    void everyIconDraws(Screen screen) {
        JavaFxToolkit.onFxThread(() -> {
            SVGPath icon = new SVGPath();
            icon.setContent(NavigationIcons.pathFor(screen));

            assertTrue(icon.getBoundsInLocal().getWidth() > 0
                            && icon.getBoundsInLocal().getHeight() > 0,
                    screen + " 아이콘이 빈 모양입니다. 경로를 확인하세요: "
                            + NavigationIcons.pathFor(screen));
        });
    }

    /** 24 칸 안에 그려야 나란히 놓았을 때 크기가 들쭉날쭉하지 않다. */
    @ParameterizedTest
    @EnumSource(Screen.class)
    @DisplayName("아이콘이 24 칸 안에 들어온다")
    void everyIconFitsTheBox(Screen screen) {
        JavaFxToolkit.onFxThread(() -> {
            SVGPath icon = new SVGPath();
            icon.setContent(NavigationIcons.pathFor(screen));

            assertTrue(icon.getBoundsInLocal().getMaxX() <= 24.5
                            && icon.getBoundsInLocal().getMaxY() <= 24.5,
                    screen + " 아이콘이 상자를 넘습니다: " + icon.getBoundsInLocal());
        });
    }

    @Test
    @DisplayName("아이콘이 없는 화면은 없다")
    void noScreenIsMissingAnIcon() {
        for (Screen screen : Screen.values()) {
            assertNotNull(NavigationIcons.pathFor(screen), screen.name());
        }
    }
}
