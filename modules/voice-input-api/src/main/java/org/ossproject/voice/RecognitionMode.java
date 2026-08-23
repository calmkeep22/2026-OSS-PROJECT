package org.ossproject.voice;

/**
 * 인식기가 무엇을 알아들을 수 있는지.
 *
 * <p>이 값이 화면에 그대로 나가야 한다. 자유롭게 말해도 되는 줄 알고 길게 말했는데
 * 정해진 명령어만 받는 인식기였다면, 사용자는 자기 발음이 나쁜 줄 안다. 화면을 볼 수
 * 없는 사용자에게는 되돌아오는 정보가 소리뿐이라 더 그렇다.
 */
public enum RecognitionMode {

    /**
     * 미리 등록한 문장만 알아듣는다. Windows 내장 인식기가 이렇다.
     *
     * <p>대신 등록된 문장에 대해서는 빠르고 정확하다.
     */
    COMMAND_GRAMMAR("정해진 명령어만 알아듣습니다"),

    /** 자유롭게 말해도 알아듣는다. 대신 느리고 종목명을 자주 틀린다. */
    FREE_SPEECH("자유롭게 말해도 됩니다"),

    /** 쓸 수 있는 인식기가 없다. */
    UNAVAILABLE("음성 인식을 쓸 수 없습니다");

    private final String description;

    RecognitionMode(String description) {
        this.description = description;
    }

    /** 사용자에게 읽어 줄 설명. */
    public String description() {
        return description;
    }
}
