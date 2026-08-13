package org.ossproject.desktop.accessibility;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;

import java.util.ArrayList;
import java.util.List;

/** 실행 중인 화면에서 키보드 포커스와 접근 가능한 이름 누락을 찾는다. */
public final class AccessibilityAudit {
    public record Issue(String code, String message, Node node) {}

    public List<Issue> audit(Node root) {
        List<Issue> issues = new ArrayList<>();
        visit(root, issues); return List.copyOf(issues);
    }

    private void visit(Node node, List<Issue> issues) {
        if (isInteractive(node) && accessibleName(node).isBlank()) {
            issues.add(new Issue("MISSING_ACCESSIBLE_NAME",
                    node.getClass().getSimpleName() + "에 접근 가능한 이름이 없습니다.", node));
        }
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(child -> visit(child, issues));
    }

    private boolean isInteractive(Node node) {
        return node.isFocusTraversable() || node instanceof ButtonBase || node instanceof TextInputControl
                || node instanceof ComboBoxBase<?> || node instanceof TableView<?> || node instanceof ListView<?>
                || node instanceof Slider || node instanceof Spinner<?>;
    }

    private String accessibleName(Node node) {
        if (hasText(node.getAccessibleText())) return node.getAccessibleText().trim();
        if (node instanceof Labeled labeled && hasText(labeled.getText())) return labeled.getText().trim();
        if (node instanceof TextInputControl input && hasText(input.getPromptText())) return input.getPromptText().trim();
        if (node instanceof ComboBoxBase<?> combo && combo.getValue() != null) return combo.getValue().toString();
        return "";
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
