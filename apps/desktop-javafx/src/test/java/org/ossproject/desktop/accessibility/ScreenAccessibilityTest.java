package org.ossproject.desktop.accessibility;

import javafx.scene.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.orderbook.DepthChartCanvas;
import org.ossproject.desktop.orderbook.OrderBookLadderView;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 화면 조각에 접근성 검사기를 돌린다.
 *
 * <p>초점을 받을 수 있는데 접근 가능한 이름이 없으면 스크린리더가 "버튼" 이라고만 읽는다.
 * 무엇을 하는 버튼인지 알 수 없다. 이 검사는 사람이 매번 확인하지 않아도 되게 한다.
 *
 * <p>여기서 만드는 것은 서비스 없이 세울 수 있는 화면 조각이다. 전체 화면은 조립 루트가
 * 필요해서 아직 다루지 못한다. 화면을 하나씩 이 검사에 넣어 가는 것이 목표다.
 */
@ExtendWith(JavaFxToolkit.class)
class ScreenAccessibilityTest {

    private final AccessibilityAudit audit = new AccessibilityAudit();

    private void assertNoMissingNames(String what, Node root) {
        List<AccessibilityAudit.Issue> issues = audit.audit(root);
        assertTrue(issues.isEmpty(), () -> what + " 에 이름 없는 조작 요소가 있습니다: "
                + issues.stream().map(AccessibilityAudit.Issue::message).collect(Collectors.joining(", ")));
    }

    @Test
    @DisplayName("호가창의 모든 조작 요소에 이름이 있다")
    void orderBookLadderNamesEverythingFocusable() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView view = new OrderBookLadderView("삼성전자");
            view.showUnavailable("호가를 기다리고 있습니다.");

            assertNoMissingNames("호가창", view.root());
        });
    }

    /** 그래프는 스크린리더가 읽을 수 없다. 이름이라도 있어야 무엇인지 알 수 있다. */
    @Test
    @DisplayName("깊이 그래프에 접근 가능한 이름이 있다")
    void depthChartCarriesAName() {
        JavaFxToolkit.onFxThread(() -> {
            DepthChartCanvas canvas = new DepthChartCanvas();

            assertNoMissingNames("깊이 그래프", canvas);
            assertTrue(canvas.getAccessibleText() != null && !canvas.getAccessibleText().isBlank(),
                    "그래프가 무엇인지 알릴 이름이 필요합니다");
        });
    }

    /**
     * 검사기가 실제로 잡는지 확인한다.
     *
     * <p>통과만 하고 아무것도 지키지 않는 검사는 없느니만 못하다. 이름 없는 단추를 넣어
     * 잡히는 것을 보인다. 이 단추는 툴킷 없이는 만들 수도 없었다.
     */
    @Test
    @DisplayName("이름 없는 단추를 실제로 잡아낸다")
    void actuallyCatchesAnUnnamedControl() {
        JavaFxToolkit.onFxThread(() -> {
            javafx.scene.layout.VBox panel = new javafx.scene.layout.VBox(
                    new javafx.scene.control.Button());

            List<AccessibilityAudit.Issue> issues = audit.audit(panel);

            assertEquals(1, issues.size(), "이름 없는 단추 하나를 잡아야 합니다");
            assertEquals("MISSING_ACCESSIBLE_NAME", issues.get(0).code());
        });
    }
}
