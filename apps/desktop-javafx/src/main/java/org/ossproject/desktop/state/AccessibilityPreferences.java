package org.ossproject.desktop.state;

/** 스크린리더·저시력·키보드 사용자를 위한 로컬 접근성 설정. */
public record AccessibilityPreferences(
        boolean speechEnabled,
        boolean soundEnabled,
        boolean keyboardGuidanceEnabled,
        boolean reducedMotionEnabled,
        boolean largeTextEnabled,
        boolean highContrastEnabled,
        String informationDensity,
        String voiceName,
        double speechRate,
        int speechVolume
) {
    public static final AccessibilityPreferences DEFAULT = new AccessibilityPreferences(
            false, true, true, true, true, false, "표준", "", 1.0, 100);

    public AccessibilityPreferences {
        informationDensity = switch (informationDensity == null ? "" : informationDensity.trim()) {
            case "간단히", "표준", "자세히" -> informationDensity.trim();
            default -> "표준";
        };
        voiceName = voiceName == null ? "" : voiceName.trim();
        speechRate = Double.isFinite(speechRate) ? Math.max(0.5, Math.min(2.0, speechRate)) : 1.0;
        speechVolume = Math.max(0, Math.min(100, speechVolume));
    }
}
