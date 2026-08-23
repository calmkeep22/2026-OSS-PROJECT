package org.ossproject.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제로 돌려 나온 인식 결과를 그대로 넣어 본다.
 *
 * <p>2026-08-23, faster-whisper 1.2.1, {@code base} 모델, int8, CPU(i7-7500U 2코어),
 * 어휘를 {@code initial_prompt} 로 물려준 상태. 한국어 다섯 마디를 합성음으로 만들어
 * 돌린 결과를 손대지 않고 옮겼다. 모델마다 새 프로세스로 격리해 쟀다.
 *
 * <p>같은 기계에서 같은 발화로 잰 것:
 *
 * <pre>
 *   whisper tiny    한 마디 1.46초   글자 0/5   반복 쓰레기가 섞여 나옴
 *   whisper base    한 마디 2.14초   글자 3/5   &lt;- 고른 것
 *   whisper small   한 마디 9.87초   글자 2/5   느려서 못 씀
 *   vosk small ko   한 마디 2.74초   글자 0/5   느리고 더 틀림
 * </pre>
 *
 * <p>글자만 놓고 고르면 셋 중 무엇도 쓸 수 없다. 그런데 아래 검사대로 파서를 거치면
 * {@code base} 는 다섯 마디가 모두 올바른 명령이 된다. 그래서 가장 크고 느린 모델이
 * 아니라 {@code base} 를 골랐다.
 *
 * <p>이 검사가 지키려는 것은 파서의 정확도가 아니라 <b>어느 모델을 쓸 수 있는가</b> 다.
 * 인식기는 종목 목록을 모르고 파서는 안다. 그 차이가 모델 두 단계 값어치를 한다.
 * 이 검사가 깨지면 그 전제가 무너진 것이므로 모델 선택을 다시 해야 한다.
 *
 * <p>재는 기계가 놀고 있어야 한다. 처음에는 다른 프로그램이 CPU 를 100% 쓰는 채로
 * 재서 {@code small} 이 3.3배와 8배로 갈렸고, 그 값을 믿고 whisper 를 접을 뻔했다.
 * 다시 잴 일이 있으면 먼저 부하부터 확인한다.
 */
class MeasuredWhisperOutputTest {

    private static final List<KnownStock> KNOWN = List.of(
            new KnownStock("005930", "삼성전자"),
            new KnownStock("000660", "SK하이닉스", List.of("에스케이하이닉스", "하이닉스")),
            new KnownStock("035720", "카카오"));

    private final VoiceCommandParser parser = new VoiceCommandParser(KNOWN);

    /** 어휘를 미리 물려준 {@code base} 가 낸 것. 다섯 마디 모두 명령이 되어야 한다. */
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
            // 2026-08-23 깨끗한 환경에서 잰 것
            "'삼성전자 매수, 열주',        BUY,              005930",
            "'관심종목 보여줘.',           OPEN_WATCHLIST,   ",
            "'SK하이닉스, 현재가 알려줘.',  QUOTE,            000660",
            "'청각 차트 열어줘.',          OPEN_RADIO_CHART, ",
            "'카카오 뉴스 읽어줘.',        OPEN_NEWS,        035720",
            // 서버(/transcribe)를 실제로 띄우고 어휘를 물려 받은 것. 확신도 0.687~0.912.
            "'삼성전자, 매수, 열주',       BUY,              005930",
            "'SK하이닉스, 현재가 알려줘',   QUOTE,            000660",
            "'청각차트 열어줘',            OPEN_RADIO_CHART, ",
            "'카카오, 뉴스, 일거좋아',      OPEN_NEWS,        035720",
            // 같은 모델이 다른 번에 낸 것. 인식기는 같은 발화도 매번 다르게 적는다.
            "'삼성전자 매수, 열쒤',        BUY,              005930",
            "'카카오 뉴스, 읽어줘.',       OPEN_NEWS,        035720",
            "'관심 좀 목 보여줘',          OPEN_WATCHLIST,   ",
            "'청각착도 열어줘.',           OPEN_RADIO_CHART, "
    })
    @DisplayName("어휘를 물려준 base 의 출력은 모두 명령이 된다")
    void promptedBaseOutputBecomesCommands(String heard, Intent expected, String symbol) {
        VoiceCommand command = parser.parse(heard);

        assertEquals(expected, command.intent(), heard + " 를 알아보지 못했다");
        if (symbol == null || symbol.isBlank()) return;
        assertEquals(symbol, command.stock().orElseThrow().symbol());
    }

    /** "열쒤" 은 열 주다. 인식기가 받침을 틀려도 수량은 살아야 한다. */
    @Test
    void 받침을_틀린_수량도_읽는다() {
        assertEquals(10, parser.parse("삼성전자 매수, 열쒤").quantity().orElseThrow());
    }

    /**
     * 어휘를 물려주지 않으면 종목명이 살아남지 못한다.
     *
     * <p>같은 {@code base} 가 어휘 없이는 "에스케이하이닉스" 를 "SKINIX" 로, "카카오" 를
     * "다가오" 로 적었다. 파서도 이것까지는 못 붙인다 — 붙일 만큼 기준을 낮추면 엉뚱한
     * 종목에 붙게 되고, 그 종목으로 주문이 나간다.
     *
     * <p>그래서 종목을 못 찾았을 때 넘겨짚지 않고 되묻는지를 검사한다. 이 검사는 파서가
     * 못하는 일을 못한다고 못박아 두는 것이고, 동시에 인식기에 어휘를 반드시 물려주어야
     * 하는 이유이기도 하다.
     */
    @ParameterizedTest
    @CsvSource({"'SKINIX 현재가 알려줘'", "'다가오 뉴스 일거죠'"})
    void 어휘를_안_물려주면_종목을_잃고_되묻는다(String heard) {
        VoiceCommand command = parser.parse(heard);

        assertTrue(command.stock().isEmpty(), "붙이면 안 되는 것을 붙였다");
        assertFalse(command.readyToRun(), "종목을 모르면 실행하지 말고 되물어야 한다");
        assertTrue(command.confirmationQuestion().contains(heard.strip()),
                "들은 말을 그대로 들려주어야 사용자가 무엇이 틀렸는지 안다");
    }
}
