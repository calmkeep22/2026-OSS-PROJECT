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
        String microphoneName,
        double speechRate,
        int speechVolume
) {
    public static final AccessibilityPreferences DEFAULT = new AccessibilityPreferences(
            false, true, true, true, true, false, "표준", "", "", 1.0, 100);

    public AccessibilityPreferences {
        informationDensity = switch (informationDensity == null ? "" : informationDensity.trim()) {
            case "간단히", "좁게" -> "좁게";
            case "자세히", "넓게" -> "넓게";
            case "표준" -> "표준";
            default -> "표준";
        };
        voiceName = voiceName == null ? "" : voiceName.trim();
        microphoneName = microphoneName == null ? "" : microphoneName.trim();
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
                informationDensity, voiceName, microphoneName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withSoundEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, value, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, microphoneName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withKeyboardGuidanceEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, value,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, microphoneName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withReducedMotionEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                value, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, microphoneName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withLargeTextEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, value, highContrastEnabled,
                informationDensity, voiceName, microphoneName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withHighContrastEnabled(boolean value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, value,
                informationDensity, voiceName, microphoneName, speechRate, speechVolume);
    }

    public AccessibilityPreferences withInformationDensity(String value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                value, voiceName, microphoneName, speechRate, speechVolume);
    }

    /** 음성 설정은 합성기에서 읽어 채운다. */
    public AccessibilityPreferences withVoice(String voice, double rate, int volume) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voice, microphoneName, rate, volume);
    }

    public AccessibilityPreferences withVoiceName(String value) {
        return withVoice(value, speechRate, speechVolume);
    }

    public AccessibilityPreferences withSpeechRate(double value) {
        return withVoice(voiceName, value, speechVolume);
    }

    public AccessibilityPreferences withSpeechVolume(int value) {
        return withVoice(voiceName, speechRate, value);
    }

    /**
     * 쓸 마이크를 정한다. 빈 값이면 기본 장치.
     *
     * <p>자바가 고르는 기본 장치가 운영체제의 기본을 따라가지 않는다. 실측에서 기본은
     * 소리가 하나도 안 들어오는 장치였고, 사용자가 쓰던 이어폰은 목록의 다른 자리에
     * 있었다. 그래서 고른 것을 기억해 둔다.
     */
    public AccessibilityPreferences withMicrophoneName(String value) {
        return new AccessibilityPreferences(speechEnabled, soundEnabled, keyboardGuidanceEnabled,
                reducedMotionEnabled, largeTextEnabled, highContrastEnabled,
                informationDensity, voiceName, value, speechRate, speechVolume);
    }

    /**
     * 합성기에 넘길 값.
     *
     * <p>화면이 조립하지 않는다. 설정 화면이 직접 {@code SpeechOptions} 를 만들어
     * 합성기에 넣고 저장은 다른 곳에서 하면, 한쪽만 도는 경우가 생긴다 — 소리는 바뀌었는데
     * 다음 실행 때 되돌아가거나 그 반대다. 여기서 한 번만 만들면 둘이 갈라지지 않는다.
     *
     * <p>빈 음성 이름은 {@code null} 로 바꾼다. {@code SpeechOptions} 에서 그것이 시스템
     * 기본 음성을 뜻한다.
     */
    public org.ossproject.accessibility.notification.SpeechOptions speechOptions() {
        return new org.ossproject.accessibility.notification.SpeechOptions(
                speechRate, speechVolume, voiceName.isBlank() ? null : voiceName);
    }
}
