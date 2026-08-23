package org.ossproject.voice;

import java.time.Duration;
import java.util.List;

/**
 * 말을 받아 오는 창구.
 *
 * <p>뒤에 무엇이 서든 앱은 이것만 본다. Windows 내장 인식기든 whisper 든, 아무것도 없든
 * 화면 코드는 달라지지 않는다.
 *
 * <p>{@link #available()} 이 거짓일 때 조용히 아무 일도 하지 않으면 안 된다. 화면을 볼 수
 * 없는 사용자는 마이크가 안 켜진 것인지 자기 말이 안 들린 것인지 구분할 수 없다.
 * {@link #unavailableReason()} 을 소리로 읽어 주어야 한다.
 */
public interface VoiceInputPort extends AutoCloseable {

    /**
     * 한마디를 듣고 돌려준다. 말이 끝나거나 {@code limit} 이 지나면 돌아온다.
     *
     * <p>부르는 쪽을 막는다. 화면 스레드에서 부르면 안 된다.
     *
     * @return 알아들은 말. 아무 말도 못 알아들었으면 {@link Transcript#nothing}.
     */
    Transcript listen(Duration limit);

    /**
     * 인식기에 알아들을 문장을 미리 알려 준다.
     *
     * <p>{@link RecognitionMode#COMMAND_GRAMMAR} 인 인식기는 이 목록에 없는 말을 알아듣지
     * 못한다. 자유 발화 인식기는 참고만 하거나 무시한다. 보유·관심 종목이 바뀌면 다시
     * 부른다.
     *
     * <p>성공 여부를 돌려주는 이유는 실패가 조용하기 때문이다. 서버가 아직 기동 중일 때
     * 등록하면 실패하는데, 그것을 모르고 넘어가면 이후 계속 종목명을 놓친다. 부르는 쪽이
     * 다시 걸 수 있어야 한다.
     *
     * @return 등록됐으면 참. 등록할 것이 없는 인식기도 참이다.
     */
    default boolean useVocabulary(List<String> phrases) {
        return true;
    }

    boolean available();

    /** 왜 못 쓰는지. 쓸 수 있으면 빈 문자열. 사용자에게 그대로 읽어 준다. */
    String unavailableReason();

    RecognitionMode mode();

    @Override
    void close();

    /**
     * 쓸 수 없는 창구. 이유를 들고 다닌다.
     *
     * <p>인식기를 세우지 못했을 때 {@code null} 을 넘기지 않기 위한 것이다. null 이면
     * 부르는 쪽마다 검사해야 하고, 한 곳만 빠뜨려도 앱이 죽는다. 무엇보다 왜 못 쓰는지를
     * 사용자에게 말해 줄 수 없게 된다.
     */
    static VoiceInputPort unavailable(String reason) {
        String given = reason == null || reason.isBlank()
                ? "음성 인식을 쓸 수 없습니다." : reason;
        return new VoiceInputPort() {
            @Override public Transcript listen(Duration limit) {
                return Transcript.nothing(RecognitionMode.UNAVAILABLE);
            }

            @Override public boolean useVocabulary(List<String> phrases) {
                return false;
            }

            @Override public boolean available() {
                return false;
            }

            @Override public String unavailableReason() {
                return given;
            }

            @Override public RecognitionMode mode() {
                return RecognitionMode.UNAVAILABLE;
            }

            @Override public void close() {
            }
        };
    }
}
