package org.ossproject.desktop.voice;

import org.ossproject.desktop.navigation.Screen;
import org.ossproject.voice.Intent;
import org.ossproject.voice.KnownStock;
import org.ossproject.voice.Transcript;
import org.ossproject.voice.VoiceCommand;
import org.ossproject.voice.VoiceCommandParser;
import org.ossproject.voice.VoiceInputPort;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 마이크 단추를 누른 뒤 벌어지는 일.
 *
 * <p>듣기는 오래 걸린다. 실측에서 녹음 2초에 인식 2~6초였다. 화면 스레드에서 하면
 * 그동안 앱이 통째로 멈춘다. 그래서 듣고 옮기는 일은 배경에서 하고, 그 결과로 화면을
 * 건드리는 일만 화면 스레드로 돌려보낸다.
 *
 * <p>무엇을 시켰는지까지만 알고 어떻게 하는지는 {@link VoiceActions} 에 맡긴다.
 * 그래야 화면 없이 순서를 검사할 수 있다 — 특히 "주문이 사람 확인 없이 나가지
 * 않는가" 를.
 */
public final class VoiceCommandController {

    /** 한 번에 듣는 시간. 명령 한마디는 실측에서 2~3초였다. */
    private static final Duration LISTEN_LIMIT = Duration.ofSeconds(8);

    /**
     * 신호음이 잦아들 때까지. 이만큼 늦게 녹음을 시작한다.
     *
     * <p>신호음보다 넉넉해야 한다. 짧으면 신호음 꼬리가 녹음 앞머리에 들어가고, 그것이
     * 배경 소음을 재는 구간이라 문턱이 밀려 올라간다.
     */
    private static final Duration BEEP_SETTLE = Duration.ofMillis(380);

    private final VoiceInputPort voice;
    private final Executor background;
    private final Executor onScreenThread;
    private final VoiceActions actions;

    /** 듣는 중에 또 누르면 무시한다. 마이크는 하나뿐이다. */
    private final AtomicBoolean listening = new AtomicBoolean();

    private volatile VoiceCommandParser parser = new VoiceCommandParser(List.of());

    /**
     * 어휘가 서버에 실제로 등록됐는지.
     *
     * <p>앱을 켜자마자 등록하면 서버가 아직 기동 중이라 실패한다. 그것을 기억해 두지
     * 않으면 서버가 준비된 뒤에도 영영 다시 걸지 않고, 사용자는 종목명이 계속 안 붙는
     * 이유를 알 수 없다.
     */
    private volatile boolean vocabularyRegistered;

    public VoiceCommandController(VoiceInputPort voice, Executor background,
                                  Executor onScreenThread, VoiceActions actions) {
        this.voice = voice;
        this.background = background;
        this.onScreenThread = onScreenThread;
        this.actions = actions;
    }

    /**
     * 알아들을 종목을 알려 준다. 보유·관심 목록이 바뀔 때마다 부른다.
     *
     * <p>이것을 하지 않으면 인식기가 종목명을 통째로 놓친다. 실측에서 "카카오" 가
     * "다가오" 로 나왔고 파서도 그것은 못 고친다.
     */
    public void updateVocabulary(List<KnownStock> known) {
        parser = new VoiceCommandParser(known);
        vocabularyRegistered = false;
        background.execute(this::pushVocabulary);
    }

    /**
     * 어휘를 서버에 올린다. 실패는 조용히 넘기고 다음 듣기에서 다시 건다.
     *
     * <p>여기서 사용자에게 말을 걸지 않는다. 시키지도 않은 일로 소리가 나면 화면을 볼 수
     * 없는 사용자는 무슨 일이 벌어졌는지 알 수 없다.
     */
    private void pushVocabulary() {
        try {
            // 문장이 아니라 낱말을 준다. 문장을 물리면 인식기가 그 문장을 되뱉는다.
            vocabularyRegistered = voice.useVocabulary(parser.vocabularyTerms());
        } catch (RuntimeException failed) {
            vocabularyRegistered = false;
        }
    }

    public boolean listening() {
        return listening.get();
    }

    /** 한마디를 듣고 실행한다. 이미 듣는 중이면 아무 일도 하지 않는다. */
    public void listenOnce() {
        if (!listening.compareAndSet(false, true)) return;
        background.execute(() -> {
            try {
                String reason = voice.unavailableReason();
                if (!reason.isEmpty()) {
                    onScreenThread.execute(() -> actions.tell(reason));
                    return;
                }
                // 서버가 이제 막 준비됐을 수 있다. 아직 못 올렸으면 지금 올린다.
                // 이것 없이 듣기 시작하면 종목명을 통째로 놓친다.
                if (!vocabularyRegistered) pushVocabulary();

                // 말을 멈추고 신호음만 낸 뒤 녹음한다. 말로 알리면 그 말이 녹음된다.
                onScreenThread.execute(actions::startedListening);
                settleBeforeRecording();

                Transcript heard = voice.listen(LISTEN_LIMIT);
                VoiceCommand command = parser.parse(heard);
                onScreenThread.execute(() -> dispatch(heard, command));
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null
                        ? "음성 인식에 실패했습니다." : failure.getMessage();
                onScreenThread.execute(() -> actions.tell(message));
            } finally {
                listening.set(false);
            }
        });
    }

    /**
     * 신호음이 끝나기를 잠깐 기다린다.
     *
     * <p>소리 내기는 다른 스레드에서 돌아간다. 곧바로 녹음하면 신호음이 배경 소음을
     * 재는 구간에 들어가 문턱을 밀어 올린다. 그러면 진짜 말이 조용한 것으로 보인다.
     */
    private void settleBeforeRecording() {
        try {
            Thread.sleep(BEEP_SETTLE.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 화면 스레드에서 부른다. 검사에서 직접 부를 수 있게 열어 둔다. */
    void dispatch(Transcript heard, VoiceCommand command) {
        if (!heard.heardSomething()) {
            // 무엇을 해볼 수 있는지 함께 말한다. 화면을 볼 수 없는 사용자에게 "못 들었다"
            // 만 남기면 자기 발음을 탓하게 되는데, 정작 흔한 원인은 입력 볼륨이다.
            actions.tell("아무 말도 듣지 못했습니다. 신호음이 난 뒤에 말씀해주세요."
                    + " 계속 안 되면 윈도우 소리 설정에서 마이크 입력 볼륨을 올려주세요.");
            return;
        }
        if (command.intent() == Intent.UNKNOWN) {
            actions.tell(command.confirmationQuestion());
            return;
        }
        if (command.requiresConfirmation()) {
            handleRisky(command);
            return;
        }
        if (command.intent().requiresStock() && command.stock().isEmpty()) {
            if (command.hasStockHint()) {
                // 아는 종목은 아니지만 종목명일 수 있다. 앱의 진짜 검색에 물어본다.
                actions.tell("\"" + command.stockHint() + "\" 종목을 찾고 있습니다.");
                actions.findStock(command.stockHint(),
                        found -> run(VoiceCommand.of(command.intent(), found, heard.text())));
                return;
            }
            actions.tell("\"" + heard.text() + "\" 로 들었습니다. 어느 종목인지 알아듣지 못했습니다.");
            return;
        }
        // 무엇으로 들었는지 언제나 함께 말한다.
        //
        // 예전에는 확신도가 높으면 결과만 짧게 말했다. 그런데 인식기의 확신도는
        // 자기가 옮긴 글자에 대한 것이지 사용자가 시킨 일에 대한 것이 아니다.
        // 자신 있게 틀리는 경우가 있고, 그때가 하필 제일 위험하다 — 사용자는
        // 엉뚱한 화면에 와서야 뭔가 잘못된 줄 알고, 무엇으로 들렸는지는 끝내 모른다.
        // 실제로 "이상감지" 라고 말했는데 다른 것으로 들은 일이 있었고, 그때
        // 확인할 방법이 없었다. 한 마디 길어지는 값은 그보다 싸다.
        actions.tell(command.confirmationQuestion());
        run(command);
    }

    /**
     * 되돌릴 수 없는 일. 여기서 실행하지 않는다.
     *
     * <p>주문은 화면만 채우고 사람이 기존 재확인 창을 거치게 한다. 취소는 무엇을
     * 취소할지 사람이 목록에서 고르게 한다.
     */
    private void handleRisky(VoiceCommand command) {
        if (command.intent() == Intent.CANCEL_ORDER) {
            actions.tell("미체결 주문 목록입니다. 취소할 주문을 골라주세요.");
            actions.openPendingOrders();
            return;
        }
        if (command.stock().isEmpty() || command.quantity().isEmpty()) {
            actions.tell(command.confirmationQuestion());
            return;
        }
        actions.tell(command.confirmationQuestion() + " 주문 화면을 채워 두었습니다."
                + " 확인 단추를 누르면 진행합니다.");
        actions.prepareOrder(command.stock().orElseThrow(),
                command.intent() == Intent.BUY, command.quantity().getAsInt());
    }

    private void run(VoiceCommand command) {
        KnownStock stock = command.stock().orElse(null);
        switch (command.intent()) {
            case OPEN_HOME -> actions.navigate(Screen.DASHBOARD);
            case OPEN_SEARCH -> actions.navigate(Screen.SEARCH);
            case OPEN_WATCHLIST -> actions.navigate(Screen.WATCHLIST);
            case OPEN_ACCOUNT -> actions.navigate(Screen.ACCOUNT);
            case OPEN_ANOMALY -> actions.navigate(Screen.ANOMALY);
            case OPEN_RADIO_CHART -> actions.navigate(Screen.RADIO);
            case OPEN_SETTINGS -> actions.navigate(Screen.SETTINGS);
            case OPEN_NEWS -> actions.openNews(stock);
            case OPEN_SIMILAR -> actions.openSimilar(stock);
            case QUOTE -> actions.quote(stock);
            case ADD_TO_WATCHLIST -> actions.addToWatchlist(stock);
            case REMOVE_FROM_WATCHLIST -> actions.removeFromWatchlist(stock);
            case READ_BALANCE -> actions.readBalance();
            case GO_BACK -> actions.goBack();
            case STOP_SPEECH -> actions.stopSpeech();
            case REPEAT -> actions.repeatLast();
            case SPEAK_FASTER -> actions.adjustSpeechRate(true);
            case SPEAK_SLOWER -> actions.adjustSpeechRate(false);
            case TOGGLE_LARGE_TEXT -> actions.toggleLargeText();
            case TOGGLE_HIGH_CONTRAST -> actions.toggleHighContrast();
            case HELP -> actions.help();
            case BUY, SELL, CANCEL_ORDER, UNKNOWN -> throw new IllegalStateException(
                    "여기까지 오면 안 되는 명령입니다: " + command.intent());
        }
    }
}
