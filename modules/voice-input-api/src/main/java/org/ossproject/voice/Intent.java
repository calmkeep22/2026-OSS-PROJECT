package org.ossproject.voice;

/**
 * 말로 시킬 수 있는 일.
 *
 * <p>화면 이름을 그대로 쓰지 않는다. 이 모듈은 화면을 모른다. 무엇을 하려는지만 담고,
 * 어느 화면으로 갈지는 앱이 정한다.
 */
public enum Intent {

    OPEN_HOME("홈", "홈 화면을 엽니다"),
    OPEN_SEARCH("종목 검색", "종목 검색을 엽니다"),
    OPEN_WATCHLIST("관심종목", "관심종목을 엽니다"),
    OPEN_ACCOUNT("계좌", "계좌를 조회합니다"),
    OPEN_NEWS("뉴스", "뉴스를 엽니다"),
    OPEN_RADIO_CHART("청각 차트", "청각 차트를 엽니다"),
    OPEN_ANOMALY("이상 감지", "이상 감지를 엽니다"),
    OPEN_SIMILAR("닮은 차트", "닮은 차트를 엽니다"),
    OPEN_SETTINGS("설정", "설정을 엽니다"),

    /** 직전 화면으로. 잘못 들어간 화면에서 빠져나오는 가장 흔한 길이다. */
    GO_BACK("뒤로 가기", "이전 화면으로 돌아갑니다"),

    /** 현재가를 읽어 달라. 종목이 함께 온다. */
    QUOTE("현재가 확인", "현재가를 알려드립니다"),

    /** 예수금과 평가손익을 읽어 달라. 화면을 옮기지 않고 답만 듣는다. */
    READ_BALANCE("잔고 확인", "잔고를 알려드립니다"),

    ADD_TO_WATCHLIST("관심종목 담기", "관심종목에 담습니다"),
    REMOVE_FROM_WATCHLIST("관심종목 빼기", "관심종목에서 뺍니다"),

    /** 소리 안내를 멈춰 달라. 말이 길게 이어질 때 끊는 수단이다. */
    STOP_SPEECH("안내 멈춤", "안내를 멈춥니다"),

    /**
     * 방금 안내를 다시 읽어 달라.
     *
     * <p>화면을 볼 수 없으면 놓친 안내를 되돌려 볼 방법이 없다. 눈으로 읽는 사람에게는
     * 화면에 그대로 남아 있는 것이라, 이것이 없으면 같은 조작을 처음부터 다시 해야 한다.
     */
    REPEAT("다시 듣기", "다시 들려드립니다"),

    SPEAK_FASTER("빠르게 읽기", "더 빠르게 읽습니다"),
    SPEAK_SLOWER("천천히 읽기", "더 천천히 읽습니다"),
    TOGGLE_LARGE_TEXT("큰 글씨 전환", "큰 글씨를 켜고 끕니다"),
    TOGGLE_HIGH_CONTRAST("고대비 전환", "고대비를 켜고 끕니다"),

    /** 무엇을 말할 수 있는지 알려 달라. */
    HELP("도움말", "도움말을 엽니다"),

    BUY("매수", "매수합니다"),
    SELL("매도", "매도합니다"),
    CANCEL_ORDER("주문 취소", "주문을 취소합니다"),

    /** 알아듣지 못했다. 들은 말을 그대로 되읽어 주고 끝낸다. */
    UNKNOWN("알아듣지 못함", "알아듣지 못했습니다");

    private final String label;
    private final String spoken;

    Intent(String label, String spoken) {
        this.label = label;
        this.spoken = spoken;
    }

    /** 화면과 목록에 적을 짧은 이름. */
    public String label() {
        return label;
    }

    /**
     * 실행하면서 들려줄 문장.
     *
     * <p>이름 뒤에 "합니다" 를 붙이면 말이 되지 않는다. "계좌 합니다", "뒤로 가기
     * 합니다" 처럼 들렸다. 화면을 볼 수 없는 사용자에게는 이 문장이 유일한 확인
     * 수단이라, 어색한 만큼 알아듣기도 어려워진다.
     *
     * <p>되묻는 명령(매수·매도·취소)은 이 문장을 쓰지 않는다. 그때는 "…하시겠습니까?"
     * 로 물어야 해서 어미가 다르다.
     */
    public String spoken() {
        return spoken;
    }

    /**
     * 사람에게 다시 물어봐야 하는가.
     *
     * <p>되돌릴 수 없는 일은 말 한마디로 실행하지 않는다. 잘못 알아들은 한 번이 그대로
     * 체결로 나간다. 화면을 볼 수 없는 사용자는 잘못 나간 주문을 눈으로 확인할 수도 없다.
     */
    public boolean requiresConfirmation() {
        return this == BUY || this == SELL || this == CANCEL_ORDER;
    }

    /**
     * 어느 종목인지 알아야 답할 수 있는가.
     *
     * <p>인식기가 종목명을 놓치는 일이 잦다. 실측에서 "카카오" 가 "다가오" 로 나왔다.
     * 이때 종목 없이 그냥 실행해 버리면 화면에 떠 있던 다른 종목의 뉴스를 읽어 준다.
     * 사용자는 카카오 뉴스를 들었다고 믿는다 — 화면을 볼 수 없으면 틀렸다는 것을 알
     * 방법이 없다. 조용히 틀린 답을 주느니 어느 종목이냐고 되묻는다.
     *
     * <p>화면만 여는 명령은 여기 들어가지 않는다. 종목을 고르는 자리가 그 화면 안에 있다.
     */
    public boolean requiresStock() {
        return this == QUOTE || this == OPEN_NEWS || this == OPEN_SIMILAR
                || this == ADD_TO_WATCHLIST || this == REMOVE_FROM_WATCHLIST;
    }
}
