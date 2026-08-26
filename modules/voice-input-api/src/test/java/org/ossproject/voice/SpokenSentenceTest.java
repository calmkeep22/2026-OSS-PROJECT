package org.ossproject.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실행하면서 들려주는 말.
 *
 * <p>화면을 볼 수 없는 사용자에게 이 문장이 유일한 확인 수단이다. 앱이 무엇을 하려는지
 * 여기서 못 알아들으면, 잘못 알아들은 명령을 막을 마지막 기회를 놓친다.
 *
 * <p>전에는 이름 뒤에 "합니다" 를 붙였다. "계좌 합니다", "뒤로 가기 합니다" 처럼
 * 말이 되지 않았다.
 */
class SpokenSentenceTest {

    private static VoiceCommand heard(Intent intent, String text) {
        return new VoiceCommand(intent, Optional.empty(), OptionalInt.empty(), text, "");
    }

    @Test
    @DisplayName("이름이 아니라 문장으로 말한다")
    void speaksASentenceNotALabel() {
        assertEquals("\"내 계좌\" 로 들었습니다. 계좌를 조회합니다.",
                heard(Intent.OPEN_ACCOUNT, "내 계좌").confirmationQuestion());
        assertEquals("\"뒤로\" 로 들었습니다. 이전 화면으로 돌아갑니다.",
                heard(Intent.GO_BACK, "뒤로").confirmationQuestion());
    }

    /** 되묻는 명령은 어미가 다르다. "매수합니다 하시겠습니까?" 가 되면 안 된다. */
    @Test
    @DisplayName("되묻는 명령은 이름으로 묻는다")
    void asksWithTheLabel() {
        VoiceCommand buy = new VoiceCommand(Intent.BUY,
                Optional.of(new KnownStock("005930", "삼성전자", List.of())),
                OptionalInt.of(10), "삼성전자 열 주 매수", "삼성전자");

        assertEquals("\"삼성전자 열 주 매수\" 로 들었습니다. 삼성전자 10주 매수 하시겠습니까?",
                buy.confirmationQuestion());
    }

    @Test
    @DisplayName("모든 명령에 문장이 있고 '합니다'로 끝난다")
    void everyIntentHasASentence() {
        for (Intent intent : Intent.values()) {
            String sentence = intent.spoken();
            assertNotNull(sentence, intent.name());
            assertFalse(sentence.isBlank(), intent.name() + " 에 문장이 없습니다");
            assertNotEquals(intent.label(), sentence,
                    intent.name() + " 이 이름을 그대로 쓰고 있습니다");
        }
    }
}
