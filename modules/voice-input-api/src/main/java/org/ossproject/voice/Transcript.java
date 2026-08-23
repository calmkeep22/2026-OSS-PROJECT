package org.ossproject.voice;

import java.util.Objects;

/**
 * 인식기가 알아들은 것.
 *
 * <p>{@code text} 는 인식기가 낸 말 그대로다. 고쳐서 담지 않는다. 사용자에게 되읽어 줄
 * 때 이 말을 읽어야 "제가 말한 것과 다르게 들었구나" 를 알 수 있다. 종목명을 붙이거나
 * 고치는 일은 {@link VoiceCommandParser} 가 따로 하고, 그 결과는 여기 섞지 않는다.
 *
 * @param text       알아들은 말. 빈 문자열이면 아무 말도 못 알아들은 것이다.
 * @param confidence 0.0 부터 1.0. 인식기가 확신 정도를 주지 않으면 {@link #UNKNOWN_CONFIDENCE}.
 * @param mode       어떤 인식기가 냈는지
 */
public record Transcript(String text, double confidence, RecognitionMode mode) {

    /** 인식기가 확신 정도를 알려 주지 않을 때. 지어내지 않고 모른다고 둔다. */
    public static final double UNKNOWN_CONFIDENCE = -1.0;

    public Transcript {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(mode, "mode");
        text = text.strip();
        if (confidence != UNKNOWN_CONFIDENCE && (confidence < 0.0 || confidence > 1.0)) {
            throw new IllegalArgumentException("confidence 는 0.0~1.0 또는 모름이어야 합니다: " + confidence);
        }
    }

    /** 아무 말도 못 알아들었다. */
    public static Transcript nothing(RecognitionMode mode) {
        return new Transcript("", UNKNOWN_CONFIDENCE, mode);
    }

    public boolean heardSomething() {
        return !text.isEmpty();
    }

    public boolean confidenceKnown() {
        return confidence != UNKNOWN_CONFIDENCE;
    }
}
