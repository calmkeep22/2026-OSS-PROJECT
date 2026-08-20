package org.ossproject.desktop.accessibility;

import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JavaFxToolkit.class)
class AccessibilityAuditTest {
    @Test void reportsFocusableNodeWithoutAccessibleName() {
        Region unnamed = new Region(); unnamed.setFocusTraversable(true);
        assertEquals(1, new AccessibilityAudit().audit(unnamed).size());
    }

    @Test void acceptsFocusableNodeWithAccessibleName() {
        Region named = new Region(); named.setFocusTraversable(true); named.setAccessibleText("차트 영역");
        assertTrue(new AccessibilityAudit().audit(named).isEmpty());
    }

    /**
     * 콤보박스는 목록을 스킨 안에서 ListView 로 만든다. 우리가 작성한 노드가 아니라
     * 이름을 붙일 방법이 없고, 스크린리더도 콤보박스 자체로 읽는다.
     */
    @Test void ignoresNodesCreatedByAControlSkin() {
        JavaFxToolkit.onFxThread(() -> {
            ComboBox<String> combo = new ComboBox<>();
            combo.setAccessibleText("정렬 기준");
            combo.getItems().addAll("이름순", "등락순");
            StackPane host = new StackPane(combo);
            new Scene(host, 400, 200);
            host.applyCss();
            host.layout();

            assertTrue(new AccessibilityAudit().audit(host).isEmpty(),
                    "이름 붙인 콤보박스가 스킨 때문에 문제로 잡히면 안 됩니다");
        });
    }
}
