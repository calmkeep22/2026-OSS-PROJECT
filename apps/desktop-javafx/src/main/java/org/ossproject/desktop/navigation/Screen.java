package org.ossproject.desktop.navigation;

/**
 * 데스크톱 앱에서 이동할 수 있는 최상위 화면을 정의한다.
 *
 * <p>화면 식별자를 JavaFX Application에서 분리해 View와 ViewModel이
 * 애플리케이션 구현체를 직접 참조하지 않도록 한다.</p>
 */
public enum Screen {
    DASHBOARD("홈", true, NavigationGroup.OVERVIEW),
    CONNECTION("API 연결", true, NavigationGroup.OVERVIEW),
    MARKET("시장", false, NavigationGroup.MARKET_EXPLORATION),
    SEARCH("종목검색", true, NavigationGroup.MARKET_EXPLORATION),
    STOCK_DETAIL("종목 상세", false, NavigationGroup.MARKET_EXPLORATION),
    WATCHLIST("관심종목", true, NavigationGroup.MARKET_EXPLORATION),
    SCANNER("랭킹 · 스캐너", false, NavigationGroup.MARKET_EXPLORATION),
    CONDITION("조건검색", false, NavigationGroup.MARKET_EXPLORATION),
    TRADING("주문", true, NavigationGroup.TRADING_ASSETS),
    ACCOUNT("계좌", true, NavigationGroup.TRADING_ASSETS),
    US_MARKET("미국주식", false, NavigationGroup.OVERSEAS),
    ANOMALY("이상 감지", true, NavigationGroup.ACCESSIBILITY_TOOLS),
    NOTIFICATIONS("알림", true, NavigationGroup.ACCESSIBILITY_TOOLS),
    RADIO("청각 차트", true, NavigationGroup.ACCESSIBILITY_TOOLS),
    SETTINGS("설정", true, NavigationGroup.SETTINGS);

    public enum NavigationGroup {
        OVERVIEW("개요와 연결"),
        MARKET_EXPLORATION("시장 탐색"),
        TRADING_ASSETS("거래와 자산"),
        OVERSEAS("해외 시장"),
        ACCESSIBILITY_TOOLS("알림과 접근성"),
        SETTINGS("환경 설정");

        private final String label;
        NavigationGroup(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final String label;
    private final boolean shownInSidebar;
    private final NavigationGroup navigationGroup;

    Screen(String label, boolean shownInSidebar, NavigationGroup navigationGroup) {
        this.label = label;
        this.shownInSidebar = shownInSidebar;
        this.navigationGroup = navigationGroup;
    }

    public String label() {
        return label;
    }

    public boolean shownInSidebar() {
        return shownInSidebar;
    }

    public NavigationGroup navigationGroup() {
        return navigationGroup;
    }
}
