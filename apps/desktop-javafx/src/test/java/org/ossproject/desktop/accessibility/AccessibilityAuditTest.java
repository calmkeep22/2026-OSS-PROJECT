package org.ossproject.desktop.accessibility;

import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccessibilityAuditTest {
    @Test void reportsFocusableNodeWithoutAccessibleName() {
        Region unnamed = new Region(); unnamed.setFocusTraversable(true);
        assertEquals(1, new AccessibilityAudit().audit(unnamed).size());
    }

    @Test void acceptsFocusableNodeWithAccessibleName() {
        Region named = new Region(); named.setFocusTraversable(true); named.setAccessibleText("차트 영역");
        assertTrue(new AccessibilityAudit().audit(named).isEmpty());
    }
}
