package org.ossproject.desktop.chart;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 한글 상태에서도 청각차트 단축키가 듣는지.
 *
 * <p>실제로 났던 고장이다. 한글 입력 상태에서 R(다시 듣기)과 S(전체 요약)가 통째로
 * 죽었다. 입력기가 글자 키를 삼켜 {@code KEY_PRESSED} 에는 코드도 글자도 남지 않는다 —
 * 추적을 걸어 보니 코드는 {@code UNDEFINED}, 글자는 빈 문자열이었다. 자모는
 * {@code KEY_TYPED} 로만 온다.
 *
 * <p>그래서 이 화면은 {@code KEY_TYPED} 도 함께 듣고, 거기 실려 온 자모를 여기서
 * 되짚는다. 이 되짚기를 지우면 한글 사용자에게 두 기능이 사라진다.
 */
class HangulShortcutTest {

    @Test
    @DisplayName("두벌식 자모를 원래 단축키로 되짚는다")
    void mapsJamoBackToShortcuts() {
        assertEquals(KeyCode.R, AccessibleChartView.hangulShortcut("ㄱ"), "R 자리는 ㄱ");
        assertEquals(KeyCode.S, AccessibleChartView.hangulShortcut("ㄴ"), "S 자리는 ㄴ");
    }

    @Test
    @DisplayName("단축키가 아닌 글자는 넘기지 않는다")
    void leavesOtherCharactersAlone() {
        for (String other : new String[]{"ㅁ", "가", "a", " ", "", "1"}) {
            assertEquals(KeyCode.UNDEFINED, AccessibleChartView.hangulShortcut(other),
                    "'" + other + "' 까지 가로채면 글자 입력이 막힌다");
        }
    }

    @Test
    @DisplayName("글자가 없어도 터지지 않는다")
    void survivesNull() {
        assertEquals(KeyCode.UNDEFINED, AccessibleChartView.hangulShortcut(null));
    }
}
