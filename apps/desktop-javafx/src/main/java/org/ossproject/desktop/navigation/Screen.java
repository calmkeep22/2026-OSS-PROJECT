package org.ossproject.desktop.navigation;

/**
 * 데스크톱 앱에서 이동할 수 있는 최상위 화면을 정의한다.
 *
 * <p>화면 식별자를 JavaFX Application에서 분리해 View와 ViewModel이
 * 애플리케이션 구현체를 직접 참조하지 않도록 한다.</p>
 */
public enum Screen {
    DASHBOARD("홈", true),
    CONNECTION("API 연결", true),
    MARKET("시장", true),
    SEARCH("종목검색", true),
    STOCK_DETAIL("종목 상세", false),
    WATCHLIST("관심종목", true),
    SCANNER("랭킹 · 스캐너", true),
    CONDITION("조건검색", true),
    SUPPLY("수급", true),
    TRADING("주문", true),
    ACCOUNT("계좌", true),
    US_MARKET("미국주식", true),
    NOTIFICATIONS("알림", true),
    RADIO("청각 차트", true),
    SETTINGS("설정", true);

    private final String label;
    private final boolean shownInSidebar;

    Screen(String label, boolean shownInSidebar) {
        this.label = label;
        this.shownInSidebar = shownInSidebar;
    }

    public String label() {
        return label;
    }

    public boolean shownInSidebar() {
        return shownInSidebar;
    }
}
