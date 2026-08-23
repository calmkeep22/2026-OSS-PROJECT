package org.ossproject.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 주문이 아닌 명령들.
 *
 * <p>말로 시키는 일의 대부분은 주문이 아니라 이동과 확인이다. 화면을 볼 수 없으면
 * 탭을 몇 번 눌러야 원하는 화면에 닿는지 세고 있어야 하는데, 이름을 부르면 한 번에
 * 간다. 이쪽은 되돌릴 수 있으므로 확인 없이 바로 실행한다.
 */
class UtilityCommandTest {

    private static final List<KnownStock> KNOWN = List.of(
            new KnownStock("005930", "삼성전자"),
            new KnownStock("035720", "카카오"));

    private final VoiceCommandParser parser = new VoiceCommandParser(KNOWN);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'관심종목 보여줘',      OPEN_WATCHLIST",
            "'계좌 열어줘',          OPEN_ACCOUNT",
            "'청각 차트 열어줘',      OPEN_RADIO_CHART",
            "'이상 감지 보여줘',      OPEN_ANOMALY",
            "'종목 검색 열어줘',      OPEN_SEARCH",
            "'설정 열어줘',          OPEN_SETTINGS",
            "'홈으로 가줘',          OPEN_HOME",
            "'뒤로',                GO_BACK",
            "'이전 화면',            GO_BACK",
            "'그만',                STOP_SPEECH",
            "'다시 말해줘',          REPEAT",
            "'천천히 읽어줘',        SPEAK_SLOWER",
            "'빠르게 읽어줘',        SPEAK_FASTER",
            "'큰 글씨로 바꿔줘',      TOGGLE_LARGE_TEXT",
            "'고대비 켜줘',          TOGGLE_HIGH_CONTRAST",
            "'예수금 얼마야',        READ_BALANCE",
            "'도움말',              HELP"
    })
    @DisplayName("이동과 확인은 확인 절차 없이 바로 실행한다")
    void utilitiesRunWithoutConfirmation(String heard, Intent expected) {
        VoiceCommand command = parser.parse(heard);

        assertEquals(expected, command.intent(), heard);
        assertFalse(command.requiresConfirmation(), "되돌릴 수 있는 일에 확인을 묻지 않는다");
        assertTrue(command.readyToRun(), heard + " 는 바로 실행되어야 한다");
    }

    @Test
    @DisplayName("더 구체적으로 말하면 그쪽이 이긴다")
    void longerPhraseWins() {
        // "관심종목에 담아줘" 는 "관심종목" 에도 딱 맞게 걸린다. 짧은 쪽을 고르면
        // 담아 달라는 말이 그냥 화면 열기가 된다.
        assertEquals(Intent.OPEN_WATCHLIST, parser.parse("관심종목 보여줘").intent());
        assertEquals(Intent.ADD_TO_WATCHLIST,
                parser.parse("삼성전자 관심종목에 담아줘").intent());
        assertEquals(Intent.REMOVE_FROM_WATCHLIST,
                parser.parse("삼성전자 관심종목에서 빼줘").intent());

        // "잔고" 는 계좌 화면, "잔고 알려줘" 는 읽어 달라는 말이다.
        assertEquals(Intent.OPEN_ACCOUNT, parser.parse("잔고 화면").intent());
        assertEquals(Intent.READ_BALANCE, parser.parse("잔고 알려줘").intent());
    }

    @Test
    @DisplayName("관심종목 취소가 주문 취소로 새지 않는다")
    void watchlistRemovalIsNotAnOrderCancel() {
        VoiceCommand command = parser.parse("삼성전자 관심종목 취소");

        assertEquals(Intent.REMOVE_FROM_WATCHLIST, command.intent());
        assertFalse(command.requiresConfirmation(),
                "관심종목에서 빼는 것은 되돌릴 수 있다");
    }

    @Test
    @DisplayName("담고 빼는 것은 어느 종목인지 알아야 한다")
    void watchlistChangesNeedAStock() {
        VoiceCommand command = parser.parse("관심종목에 담아줘");

        assertTrue(command.stock().isEmpty());
        assertFalse(command.readyToRun(),
                "종목을 모르면 엉뚱한 종목을 담지 말고 되물어야 한다");
    }

    @Test
    @DisplayName("종목명만 말하면 현재가를 읽어 준다")
    void bareStockNameAsksForTheQuote() {
        VoiceCommand command = parser.parse("삼성전자");

        assertEquals(Intent.QUOTE, command.intent());
        assertEquals("005930", command.stock().orElseThrow().symbol());
        assertTrue(command.readyToRun());
    }
}
