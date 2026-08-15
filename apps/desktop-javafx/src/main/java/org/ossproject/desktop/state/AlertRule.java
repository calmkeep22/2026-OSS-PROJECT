package org.ossproject.desktop.state;

/** 화면 알림 규칙. 실제 금융 이벤트 규칙과 분리된 사용자 UI 상태다. */
public record AlertRule(String securityName, String condition, String threshold, boolean enabled) {
    public AlertRule {
        securityName = required(securityName, "securityName");
        condition = required(condition, "condition");
        threshold = required(threshold, "threshold");
    }

    public AlertRule toggled() {
        return new AlertRule(securityName, condition, threshold, !enabled);
    }

    public String statusText() {
        return enabled ? "활성" : "일시정지";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
