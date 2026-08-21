package org.ossproject.ai;

/**
 * 분석 신뢰도.
 *
 * <p>봉이 짧으면 모델이 거절하지 않고 신뢰도를 낮춰 답한다. 낮은 신뢰도를 감추면
 * 사용자는 근거가 얇은 값을 확실한 것으로 읽는다.
 */
public enum Confidence {
    HIGH("높음"),
    MEDIUM("보통"),
    LOW("낮음"),
    VERY_LOW("매우낮음"),
    UNKNOWN("알 수 없음");

    private final String displayName;

    Confidence(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 사용자에게 이 사실을 반드시 알려야 하는 수준인지. */
    public boolean needsWarning() {
        return this == LOW || this == VERY_LOW || this == UNKNOWN;
    }

    /** 서비스가 보내는 한국어 표기를 그대로 받는다. 모르는 값은 알 수 없음으로 둔다. */
    public static Confidence from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim()) {
            case "높음" -> HIGH;
            case "보통" -> MEDIUM;
            case "낮음" -> LOW;
            case "매우낮음" -> VERY_LOW;
            default -> UNKNOWN;
        };
    }
}
