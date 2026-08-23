package org.ossproject.finance.model.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 영문 종목명과 사람이 말하는 한글을 잇는지.
 *
 * <p>실제로 났던 고장을 막는다. "네이버" 라고 또렷하게 말했는데 앱이 아무 반응도
 * 하지 않았다. 인식기도 파서도 멀쩡했고, 거래소 등록명이 {@code NAVER} 라
 * {@code "NAVER".contains("네이버")} 가 거짓이었던 것이 원인이었다.
 */
class KoreanReadingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "NAVER,    네이버",
            "LG화학,   엘지화학",
            "KB금융,   케이비금융",
            "SK하이닉스, 에스케이하이닉스",
            "POSCO홀딩스, 포스코홀딩스",
            "KAKAO,    케이에이케이에이오",
            "HMM,      에이치엠엠",
            "삼성전자,  삼성전자"
    })
    @DisplayName("등록명을 사람이 말하는 한글로 읽는다")
    void readsNamesAsSpoken(String name, String spoken) {
        assertEquals(spoken, KoreanReading.toHangul(name));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "네이버,          NAVER",
            "엘지화학,        LG화학",
            "케이비금융,      KB금융",
            "에스케이하이닉스, SK하이닉스"
    })
    @DisplayName("말한 한글을 등록명의 영문으로 되돌린다")
    void turnsSpokenBackIntoTheRegisteredName(String spoken, String name) {
        assertEquals(name, KoreanReading.toLatin(spoken));
    }

    /**
     * 되돌릴 것이 없으면 빈 문자열이어야 한다. 원래 말을 그대로 돌려주면 부르는 쪽이
     * 똑같은 검색을 두 번 하게 된다.
     */
    @Test
    void 되돌릴_것이_없으면_비워_돌려준다() {
        assertEquals("", KoreanReading.toLatin("삼성전자"));
        assertEquals("", KoreanReading.toLatin(""));
        assertEquals("", KoreanReading.toLatin(null));
    }

    /**
     * 글자 하나만 바뀐 것은 우연일 가능성이 높다. "이마트" 의 "이" 를 E 로 보고
     * "E마트" 를 만들면 있지도 않은 이름으로 검색하게 된다.
     */
    @Test
    void 한_글자만_맞는_것은_바꾸지_않는다() {
        assertEquals("", KoreanReading.toLatin("이마트"));
        assertEquals("", KoreanReading.toLatin("지투알"));
    }

    /** 한글 단어 속 글자를 영문으로 바꾸면 안 된다. */
    @Test
    void 한글_단어_속은_건드리지_않는다() {
        assertEquals("SK하이닉스", KoreanReading.toLatin("에스케이하이닉스"));
    }

    /** 짧은 것부터 맞추면 "엘지" 가 "엘" 과 "지" 로 쪼개진다. */
    @Test
    void 긴_읽기를_먼저_맞춘다() {
        assertEquals("NAVER", KoreanReading.toLatin("네이버"));
        assertNotEquals("NAVER", KoreanReading.toLatin("네이"));
    }

    @Test
    void 두_방향이_서로_되돌아온다() {
        for (String name : new String[]{"NAVER", "LG화학", "KB금융", "HMM"}) {
            assertEquals(name, KoreanReading.toLatin(KoreanReading.toHangul(name)), name);
        }
    }
}
