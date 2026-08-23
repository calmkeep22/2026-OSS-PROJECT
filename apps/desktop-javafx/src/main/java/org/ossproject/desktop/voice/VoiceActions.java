package org.ossproject.desktop.voice;

import org.ossproject.desktop.navigation.Screen;
import org.ossproject.voice.KnownStock;

/**
 * 말로 시킨 일을 앱이 실제로 하는 자리.
 *
 * <p>{@link VoiceCommandController} 는 무엇을 시켰는지까지만 알고, 그것을 어떻게
 * 하는지는 모른다. 그래야 화면을 띄우지 않고도 명령 처리 순서를 검사할 수 있다.
 */
public interface VoiceActions {

    void navigate(Screen screen);

    void goBack();

    /** 현재가를 읽어 준다. 화면은 옮기지 않는다. */
    void quote(KnownStock stock);

    void openNews(KnownStock stock);

    void openSimilar(KnownStock stock);

    void addToWatchlist(KnownStock stock);

    void removeFromWatchlist(KnownStock stock);

    /** 예수금과 평가손익을 읽어 준다. */
    void readBalance();

    /**
     * 주문 화면을 채워 둔다. <b>주문을 내지 않는다.</b>
     *
     * <p>말로 주문을 확정하지 않는다. 잘못 알아들은 한 번이 그대로 체결로 나가고,
     * 화면을 볼 수 없는 사용자는 잘못 나간 주문을 눈으로 확인할 수도 없다. 채워만
     * 두고 사람이 기존 재확인 창을 거치게 한다.
     */
    void prepareOrder(KnownStock stock, boolean buy, int quantity);

    /** 미체결 목록으로 데려간다. 무엇을 취소할지는 사람이 고른다. */
    void openPendingOrders();

    /**
     * 아는 종목에는 없지만 종목명일 법한 말이 나왔다. 실제 종목 검색을 걸어 본다.
     *
     * <p>음성 어휘에는 보유·관심 종목만 들어간다. 그것만으로는 상장 종목 대부분을 부를
     * 수 없다 — 사용자가 "네이버" 라고 또렷하게 말해도 관심종목에 없으면 못 알아듣는다.
     * 종목명을 코드에 적어 두는 대신, 앱이 이미 쓰는 검색으로 넘긴다.
     *
     * @param hint    종목명일 법한 말
     * @param andThen 찾았을 때 그 종목으로 할 일
     */
    void findStock(String hint, java.util.function.Consumer<KnownStock> andThen);

    void stopSpeech();

    /**
     * 이제 듣기 시작한다고 알린다.
     *
     * <p><b>말로 알리면 안 된다.</b> 스피커로 나간 그 말을 마이크가 그대로 녹음한다.
     * 실제로 그렇게 됐다 — "듣고 있습니다. 말씀해주세요" 를 읽어 주고 녹음을 시작했더니
     * 인식기가 그 문장을 돌려주었다. 사용자는 아직 입도 떼지 않았다.
     *
     * <p>그래서 짧은 신호음만 낸다. 읽고 있던 안내가 있으면 그것도 멈춘다. 안 멈추면
     * 직전 답변을 읽는 소리가 그대로 다음 명령으로 들어간다.
     */
    void startedListening();

    /** 방금 안내를 다시 읽어 준다. */
    void repeatLast();

    void adjustSpeechRate(boolean faster);

    void toggleLargeText();

    void toggleHighContrast();

    void help();

    /**
     * 사용자에게 알린다. 소리와 글자 둘 다로.
     *
     * <p>음성으로 시킨 일은 음성으로 답해야 한다. 화면에만 쓰면 화면을 볼 수 없는
     * 사용자는 명령이 먹혔는지 알 수 없다.
     */
    void tell(String message);
}
