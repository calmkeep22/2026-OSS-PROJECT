package org.ossproject.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파서 검사.
 *
 * <p>"잘못 들은 말" 을 지어내지 않았다. 2026-08-23 에 faster-whisper 로 실제 한국어
 * 발화를 돌려 나온 것을 그대로 옮겼다. 지어낸 오인식으로 검사하면 실제로 나오지 않는
 * 오류만 막고 실제로 나오는 오류는 놓친다.
 */
class VoiceCommandParserTest {

    private static final List<KnownStock> KNOWN = List.of(
            new KnownStock("005930", "삼성전자"),
            new KnownStock("000660", "SK하이닉스", List.of("에스케이하이닉스", "하이닉스")),
            new KnownStock("035720", "카카오"),
            new KnownStock("000880", "한화"));

    private final VoiceCommandParser parser = new VoiceCommandParser(KNOWN);

    @Nested
    @DisplayName("실제로 잘못 들은 말을 붙인다")
    class RepairsRealMisrecognitions {

        /** whisper base 가 "관심종목 보여줘" 를 이렇게 적었다. */
        @Test void 관심_좀_목() {
            assertEquals(Intent.OPEN_WATCHLIST, parser.parse("관심 좀 목 보여줘").intent());
        }

        /** whisper base 가 "청각 차트 열어줘" 를 이렇게 적었다. */
        @Test void 청각착도() {
            assertEquals(Intent.OPEN_RADIO_CHART, parser.parse("청각착도 열어줘.").intent());
        }

        /** whisper small 이 "카카오 뉴스 읽어줘" 를 이렇게 적었다. */
        @Test void 카카오_뉴스_일거죠() {
            VoiceCommand command = parser.parse("카카오 뉴스 일거죠");
            assertEquals(Intent.OPEN_NEWS, command.intent());
            assertEquals("카카오", command.stock().orElseThrow().name());
        }

        /** 어휘를 물려준 whisper 가 "에스케이하이닉스" 를 이렇게 적었다. 별칭으로 붙는다. */
        @Test void SK하이닉스_현재가() {
            VoiceCommand command = parser.parse("SK하이닉스, 현재가 알려줘.");
            assertEquals(Intent.QUOTE, command.intent());
            assertEquals("000660", command.stock().orElseThrow().symbol());
        }
    }

    @Nested
    @DisplayName("수량을 세는 법")
    class Counting {

        /** whisper base 는 "열 주" 를 숫자로 적었다. */
        @Test void 숫자로_적힌_수량() {
            VoiceCommand command = parser.parse("삼성전자 매수 10주");
            assertEquals(Intent.BUY, command.intent());
            assertEquals(10, command.quantity().orElseThrow());
        }

        /** whisper small 은 같은 발화를 "열조" 로 적었다. 고유어 수로 읽는다. */
        @Test void 고유어로_적힌_수량() {
            assertEquals(10, parser.parse("삼성전자, 매수, 열조").quantity().orElseThrow());
        }

        @Test void 한자어로_적힌_수량() {
            assertEquals(25, parser.parse("삼성전자 이십오 주 매수").quantity().orElseThrow());
        }

        /**
         * 종목명 안의 글자가 수량이 되면 안 된다.
         *
         * <p>"한화" 의 "한" 은 고유어로 1이다. 이것을 수량으로 세면 사용자가 말하지 않은
         * 1주 주문이 나간다.
         */
        @Test void 종목명은_수량으로_세지_않는다() {
            VoiceCommand command = parser.parse("한화 매수");
            assertEquals(Intent.UNKNOWN, command.intent(),
                    "수량을 말하지 않았으므로 주문이 되면 안 된다");
            assertTrue(command.quantity().isEmpty());
        }

        /** 종목 코드 여섯 자리가 수량으로 잡히면 안 된다. */
        @Test void 종목코드는_수량이_아니다() {
            assertTrue(KoreanNumbers.quantityIn("005930 매수").isEmpty());
        }
    }

    @Nested
    @DisplayName("되돌릴 수 없는 일은 함부로 만들지 않는다")
    class NeverGuessesOrders {

        @Test void 수량을_말하지_않은_주문은_주문이_아니다() {
            assertEquals(Intent.UNKNOWN, parser.parse("삼성전자 매수해줘").intent());
        }

        @Test void 종목을_말하지_않은_주문은_주문이_아니다() {
            assertEquals(Intent.UNKNOWN, parser.parse("매수 10주").intent());
        }

        @Test void 주문은_언제나_다시_묻는다() {
            VoiceCommand command = parser.parse("삼성전자 매수 10주");
            assertTrue(command.requiresConfirmation());
            assertFalse(command.readyToRun(), "주문이 확인 없이 실행되면 안 된다");
        }

        /**
         * 화면 이동은 헐겁게 붙여도 되지만 주문은 아니다. "매도" 와 "매수" 는 한 글자
         * 차이인데, 헐겁게 붙이면 팔라는 말이 사라는 말이 된다.
         */
        @Test void 매수와_매도는_섞이지_않는다() {
            assertEquals(Intent.SELL, parser.parse("삼성전자 매도 5주").intent());
            assertEquals(Intent.BUY, parser.parse("삼성전자 매수 5주").intent());
        }
    }

    @Nested
    @DisplayName("모르면 모른다고 한다")
    class SaysSoWhenLost {

        @Test void 아무_말도_못_알아들었을_때() {
            VoiceCommand command = parser.parse("");
            assertEquals(Intent.UNKNOWN, command.intent());
            assertTrue(command.confirmationQuestion().contains("다시 말씀해"));
        }

        @Test void 엉뚱한_말은_억지로_붙이지_않는다() {
            assertEquals(Intent.UNKNOWN, parser.parse("오늘 점심 뭐 먹지").intent());
        }

        /** 들은 말을 그대로 되읽어 주어야 사용자가 무엇이 잘못됐는지 안다. */
        @Test void 들은_말을_그대로_들려준다() {
            assertTrue(parser.parse("SKI Nix 현재가").confirmationQuestion().contains("SKI Nix"));
        }
    }

    @Nested
    @DisplayName("인식기에 넘길 문장 목록")
    class Vocabulary {

        @Test void 아는_종목과_명령어가_모두_들어간다() {
            List<String> phrases = parser.grammarPhrases();
            assertTrue(phrases.contains("관심종목"));
            assertTrue(phrases.contains("삼성전자"));
            assertTrue(phrases.contains("에스케이하이닉스"), "별칭도 등록해야 한다");
            assertTrue(phrases.contains("삼성전자 매수 10주"),
                    "수량까지 붙은 문장을 등록해야 한다");
            assertFalse(phrases.contains("삼성전자 매수"),
                    "수량 없는 주문은 파서가 거절하므로 등록하면 안 된다");
        }

        /**
         * 자유 발화 인식기에는 문장이 아니라 낱말만 준다.
         *
         * <p>완성된 문장을 initial_prompt 에 넣으면 소리가 불분명할 때 그 문장을 통째로
         * 되뱉는다. 실제로 "이상감지" 라고 말했는데 "삼성전자 매수 5주, 삼성전자" 가
         * 나왔고, 그 문장은 문법 목록에 있던 것 그대로였다.
         */
        @Test void 낱말_목록에는_완성된_문장이_없다() {
            List<String> terms = parser.vocabularyTerms();

            assertTrue(terms.contains("삼성전자"));
            assertTrue(terms.contains("관심종목"));
            assertTrue(terms.contains("매수"));
            for (String term : terms) {
                assertTrue(term.chars().noneMatch(Character::isDigit),
                        "수량이 붙은 문장이 섞였다: " + term);
                assertFalse(term.contains(" "), "낱말이어야 한다: " + term);
            }
        }

        /**
         * 목록과 파서가 어긋나면 안 된다. 인식기가 알아들은 말을 파서가 못 알아보면
         * 사용자는 또렷하게 말했는데도 계속 실패한다.
         */
        @Test void 목록에_있는_말은_파서가_알아본다() {
            for (String phrase : parser.grammarPhrases()) {
                assertNotEquals(Intent.UNKNOWN, parser.parse(phrase).intent(),
                        phrase + " 를 인식기에 등록해 놓고 파서가 모른다");
            }
        }
    }
}
