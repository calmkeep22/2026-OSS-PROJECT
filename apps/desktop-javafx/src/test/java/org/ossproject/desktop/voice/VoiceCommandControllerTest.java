package org.ossproject.desktop.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.voice.KnownStock;
import org.ossproject.voice.RecognitionMode;
import org.ossproject.voice.Transcript;
import org.ossproject.voice.VoiceCommandParser;
import org.ossproject.voice.VoiceInputPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 말 한마디가 앱에서 무엇이 되는지.
 *
 * <p>화면을 띄우지 않는다. 여기서 지키려는 것은 그림이 아니라 순서다 — 특히
 * <b>주문이 사람 확인 없이 나가지 않는다</b> 는 것. 그건 화면으로는 검사하기 어렵고,
 * 틀렸을 때 되돌릴 수도 없다.
 */
class VoiceCommandControllerTest {

    private static final List<KnownStock> KNOWN = List.of(
            new KnownStock("005930", "삼성전자"),
            new KnownStock("035720", "카카오"));

    /** 무엇을 시켰는지 적어 두기만 한다. */
    private static final class Recorder implements VoiceActions {
        final List<String> done = new ArrayList<>();
        final List<String> said = new ArrayList<>();

        @Override public void navigate(Screen screen) { done.add("navigate:" + screen); }
        @Override public void goBack() { done.add("goBack"); }
        @Override public void quote(KnownStock s) { done.add("quote:" + s.symbol()); }
        @Override public void openNews(KnownStock s) { done.add("news:" + s.symbol()); }
        @Override public void openSimilar(KnownStock s) { done.add("similar:" + s.symbol()); }
        @Override public void addToWatchlist(KnownStock s) { done.add("add:" + s.symbol()); }
        @Override public void removeFromWatchlist(KnownStock s) { done.add("remove:" + s.symbol()); }
        @Override public void readBalance() { done.add("balance"); }
        @Override public void prepareOrder(KnownStock s, boolean buy, int quantity) {
            done.add("prepareOrder:" + s.symbol() + ":" + (buy ? "매수" : "매도") + ":" + quantity);
        }
        @Override public void openPendingOrders() { done.add("pendingOrders"); }
        @Override public void stopSpeech() { done.add("stopSpeech"); }
        @Override public void startedListening() { done.add("startedListening"); }

        /** 아는 종목이 아니면 앱이 진짜 검색을 건다. 여기서는 찾았다고 치고 돌려준다. */
        String searched = "";
        KnownStock willFind;
        @Override public void findStock(String hint, java.util.function.Consumer<KnownStock> andThen) {
            searched = hint;
            done.add("findStock:" + hint);
            if (willFind != null) andThen.accept(willFind);
        }
        @Override public void repeatLast() { done.add("repeat"); }
        @Override public void adjustSpeechRate(boolean faster) { done.add("rate:" + faster); }
        @Override public void toggleLargeText() { done.add("largeText"); }
        @Override public void toggleHighContrast() { done.add("highContrast"); }
        @Override public void help() { done.add("help"); }
        @Override public void tell(String message) { said.add(message); }
    }

    /** 정해 둔 말을 돌려준다. 마이크도 서버도 없다. */
    private record CannedVoice(Transcript answer, String reason) implements VoiceInputPort {
        @Override public Transcript listen(Duration limit) { return answer; }
        @Override public boolean available() { return reason.isEmpty(); }
        @Override public String unavailableReason() { return reason; }
        @Override public RecognitionMode mode() { return RecognitionMode.FREE_SPEECH; }
        @Override public void close() { }
    }

    private final Recorder recorder = new Recorder();

    private VoiceCommandController controller() {
        VoiceCommandController controller = new VoiceCommandController(
                new CannedVoice(Transcript.nothing(RecognitionMode.FREE_SPEECH), ""),
                Runnable::run, Runnable::run, recorder);
        controller.updateVocabulary(KNOWN);
        return controller;
    }

    private void hear(String text, double confidence) {
        VoiceCommandParser parser = new VoiceCommandParser(KNOWN);
        Transcript heard = new Transcript(text, confidence, RecognitionMode.FREE_SPEECH);
        controller().dispatch(heard, parser.parse(heard));
    }

    private void hear(String text) {
        hear(text, 0.9);
    }

    @Nested
    @DisplayName("되돌릴 수 없는 일은 말만으로 실행하지 않는다")
    class NeverExecutesOrders {

        @Test void 매수는_화면만_채운다() {
            hear("삼성전자 매수 10주");

            assertEquals(List.of("prepareOrder:005930:매수:10"), recorder.done);
            assertTrue(String.join(" ", recorder.said).contains("확인 단추"),
                    "무엇을 해야 주문이 나가는지 알려 주어야 한다");
        }

        @Test void 매도도_마찬가지다() {
            hear("카카오 매도 5주");

            assertEquals(List.of("prepareOrder:035720:매도:5"), recorder.done);
        }

        /** 확신도가 아무리 높아도 사람 확인을 건너뛰지 않는다. */
        @Test void 또렷하게_들려도_바로_내지_않는다() {
            hear("삼성전자 매수 10주", 1.0);

            assertTrue(recorder.done.stream().allMatch(step -> step.startsWith("prepareOrder")),
                    "주문을 낸 흔적이 있으면 안 된다: " + recorder.done);
        }

        @Test void 취소는_목록으로_데려가고_고르게_한다() {
            hear("주문 취소");

            assertEquals(List.of("pendingOrders"), recorder.done);
            assertTrue(String.join(" ", recorder.said).contains("골라주세요"));
        }

        @Test void 수량을_못_들었으면_화면도_채우지_않는다() {
            hear("삼성전자 매수해줘");

            assertTrue(recorder.done.isEmpty(), "채운 흔적: " + recorder.done);
            assertTrue(String.join(" ", recorder.said).contains("삼성전자 매수해줘"),
                    "들은 말을 되읽어 주어야 한다");
        }
    }

    @Nested
    @DisplayName("되돌릴 수 있는 일은 바로 한다")
    class RunsUtilities {

        @Test void 화면_이동() {
            hear("관심종목 보여줘");

            assertEquals(List.of("navigate:WATCHLIST"), recorder.done);
        }

        @Test void 뒤로_가기() {
            hear("뒤로");

            assertEquals(List.of("goBack"), recorder.done);
        }

        @Test void 안내_멈춤() {
            hear("그만");

            assertEquals(List.of("stopSpeech"), recorder.done);
        }

        @Test void 종목이_붙은_명령() {
            hear("카카오 뉴스 읽어줘");

            assertEquals(List.of("news:035720"), recorder.done);
        }

        @Test void 관심종목_담기() {
            hear("삼성전자 관심종목에 담아줘");

            assertEquals(List.of("add:005930"), recorder.done);
        }
    }

    @Nested
    @DisplayName("못 알아들었을 때")
    class WhenLost {

        @Test void 아무_말도_안_들렸으면_다시_말하라고_한다() {
            controller().dispatch(Transcript.nothing(RecognitionMode.FREE_SPEECH),
                    new VoiceCommandParser(KNOWN).parse(""));

            assertTrue(recorder.done.isEmpty());
            String said = String.join(" ", recorder.said);
            assertTrue(said.contains("듣지 못했습니다"), said);
            // 무엇을 해볼 수 있는지 함께 말해야 한다. "못 들었다" 만 남기면 사용자는
            // 자기 발음을 탓하게 되는데, 정작 흔한 원인은 입력 볼륨이다.
            assertTrue(said.contains("볼륨"), "해볼 것을 알려 주지 않는다: " + said);
        }

        @Test void 모르는_말은_들은_대로_돌려준다() {
            hear("오늘 점심 뭐 먹지");

            assertTrue(recorder.done.isEmpty());
            assertTrue(String.join(" ", recorder.said).contains("오늘 점심 뭐 먹지"));
        }

        /**
         * 종목을 놓쳤는데 화면에 떠 있던 종목으로 답하면, 사용자는 자기가 말한 종목의
         * 답을 들었다고 믿는다. 화면을 볼 수 없으면 틀린 줄 알 방법이 없다.
         */
        /**
         * 잘못 들은 말로 찾아보는 것까지는 괜찮다 — 찾기는 되돌릴 수 있는 일이다.
         * 다만 못 찾았으면 거기서 멈춰야 한다. 비슷한 다른 종목으로 실행하면 안 된다.
         */
        @Test void 종목을_못_찾으면_거기서_멈춘다() {
            recorder.willFind = null;   // 검색해도 안 나오는 말

            hear("다가오 뉴스 읽어줘");

            assertEquals(List.of("findStock:다가오"), recorder.done,
                    "찾지 못했는데 무언가를 실행했다");
        }

        /**
         * 확신도가 아무리 높아도 무엇으로 들었는지 함께 알린다.
         *
         * <p>인식기의 확신도는 자기가 옮긴 글자에 대한 것이지 사용자가 시킨 일에 대한
         * 것이 아니다. 자신 있게 틀리는 경우가 있고, 결과만 말하면 그때 사용자는
         * 무엇으로 들렸는지 끝내 알 수 없다.
         */
        @Test void 확신도와_무관하게_들은_말을_함께_알린다() {
            hear("관심종목 보여줘", 0.4);
            assertTrue(String.join(" ", recorder.said).contains("관심종목 보여줘"));

            recorder.said.clear();
            recorder.done.clear();
            hear("관심종목 보여줘", 1.0);

            assertEquals(List.of("navigate:WATCHLIST"), recorder.done);
            assertTrue(String.join(" ", recorder.said).contains("관심종목 보여줘"),
                    "확신도가 높아도 들은 말을 감추면 안 된다");
        }
    }

    @Nested
    @DisplayName("쓸 수 없을 때")
    class Unavailable {

        @Test void 이유를_소리로_알린다() {
            VoiceCommandController controller = new VoiceCommandController(
                    new CannedVoice(Transcript.nothing(RecognitionMode.UNAVAILABLE),
                            "이 컴퓨터에서 마이크를 찾지 못했습니다."),
                    Runnable::run, Runnable::run, recorder);

            controller.listenOnce();

            assertTrue(recorder.done.isEmpty());
            assertEquals(List.of("이 컴퓨터에서 마이크를 찾지 못했습니다."), recorder.said,
                    "조용히 아무 일도 안 하면 마이크 문제인지 알 수 없다");
        }

        @Test void 듣는_중에_또_누르면_무시한다() {
            VoiceCommandController controller = controller();

            assertFalse(controller.listening(), "부르기 전에는 듣고 있지 않다");
        }
    }

    @Nested
    @DisplayName("아는 종목이 아니어도 찾아본다")
    class LooksUpUnknownStocks {

        /**
         * 음성 어휘에는 보유·관심 종목만 들어간다. 그것만으로는 상장 종목 대부분을
         * 부를 수 없다 — 실제로 "네이버" 라고 또렷하게 말했는데 관심종목에 없어서
         * 아무 일도 일어나지 않았다.
         */
        @Test void 모르는_종목이면_앱의_검색으로_넘긴다() {
            hear("네이버 현재가 알려줘");

            assertEquals(List.of("findStock:네이버"), recorder.done);
            assertEquals("네이버", recorder.searched);
        }

        @Test void 찾으면_그_종목으로_실행한다() {
            recorder.willFind = new KnownStock("035420", "NAVER");

            hear("네이버 뉴스 보여줘");

            assertEquals(List.of("findStock:네이버", "news:035420"), recorder.done);
        }

        /** 아는 종목이면 검색을 걸지 않는다. 이미 답을 알고 있다. */
        @Test void 아는_종목은_검색하지_않는다() {
            hear("삼성전자 현재가 알려줘");

            assertEquals(List.of("quote:005930"), recorder.done);
        }

        /** 종목명일 법한 말이 없으면 넘겨짚지 않는다. */
        @Test void 남은_말이_없으면_되묻는다() {
            hear("현재가 알려줘");

            assertTrue(recorder.done.isEmpty(), "검색을 걸었다: " + recorder.done);
            assertTrue(String.join(" ", recorder.said).contains("어느 종목"));
        }
    }

    @Nested
    @DisplayName("앱이 자기 목소리를 듣지 않는다")
    class DoesNotHearItself {

        /**
         * 실제로 났던 고장이다.
         *
         * <p>녹음 직전에 "듣고 있습니다. 말씀해주세요" 를 읽어 주었더니 마이크가 그것을
         * 녹음했고, 인식기가 그 문장을 그대로 돌려주었다. 사용자는 아직 입도 떼지
         * 않았는데 7.75초가 앱의 목소리로 채워졌다.
         */
        @Test void 듣기_시작을_말로_알리지_않는다() {
            VoiceCommandController controller = new VoiceCommandController(
                    new CannedVoice(new Transcript("관심종목 보여줘", 0.9,
                            RecognitionMode.FREE_SPEECH), ""),
                    Runnable::run, Runnable::run, recorder);

            controller.listenOnce();

            assertEquals("startedListening", recorder.done.get(0),
                    "녹음 전에 할 일은 신호음이지 안내 낭독이 아니다");
            assertFalse(recorder.said.stream().anyMatch(said -> said.contains("듣고 있습니다")),
                    "이 말이 스피커로 나가면 그대로 녹음된다: " + recorder.said);
        }

        @Test void 녹음_전에_읽던_안내를_멈춘다() {
            VoiceCommandController controller = new VoiceCommandController(
                    new CannedVoice(Transcript.nothing(RecognitionMode.FREE_SPEECH), ""),
                    Runnable::run, Runnable::run, recorder);

            controller.listenOnce();

            assertTrue(recorder.done.contains("startedListening"),
                    "직전 답변을 읽는 소리가 다음 명령으로 들어간다");
        }
    }
}
