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
            case "간단히", "좁게" -> "좁게";
            case "자세히", "넓게" -> "넓게";
            case "표준" -> "표준";
            default -> "표준";
        };
        voiceName = voiceName == null ? "" : voiceName.trim();
        speechRate = Double.isFinite(speechRate) ? Math.max(0.5, Math.min(2.0, speechRate)) : 1.0;
        speechVolume = Math.max(0, Math.min(100, speechVolume));
    }

    /**
     * 하나만 바꾼 새 설정.
     *
     * <p>화면이 값 여덟 개를 따로 들고 있으면 저장할 때마다 다시 묶어야 하고, 묶는 자리를
     * 한 곳만 빠뜨려도 설정이 조용히 사라진다. 설정을 통째로 들고 하나씩 바꿔 나간다.
     */
    public AccessibilityPreferences withSpeechEnabled(boolean value) {
        return new AccessibilityPreferences(value, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withSoundEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, value, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withKeyboardGuidanceEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, value,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withReducedMotionEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                value, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withLargeTextEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, value, highContrastEnabled,
                informationDensity, voiceName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withHighContrastEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, value,
                informationDensity, voiceName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withInformationDensity(String value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                value, voiceName, speechRate, speechVolume);
    }

    /** 음성 설정은 합성기에서 읽어 채운다. */
    public AccessibilityPreferences withVoice(String voice, double rate, int volume) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voice, rate, volume);
    }
}
