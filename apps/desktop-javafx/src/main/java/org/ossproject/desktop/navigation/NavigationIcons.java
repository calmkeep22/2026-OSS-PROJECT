package org.ossproject.desktop.navigation;

import java.util.EnumMap;
import java.util.Map;

/**
 * 사이드바 아이콘 경로.
 *
 * <p>화면 코드에서 떼어 낸 이유는 검사하기 위해서다. SVG 경로가 잘못돼도 컴파일러는
 * 아무 말이 없고 JavaFX 도 예외 없이 빈 모양을 그린다. 실제로 주문 아이콘의 호 명령이
 * 인자를 여덟 개 받고 있었는데 — 일곱 개여야 한다 — 아무도 몰랐다. 사이드바에 빈 칸이
 * 하나 있었고 누르면 눌리기는 했다.
 *
 * <p>아이콘만으로 뜻을 전하지 않는다. 사이드바 항목에는 이름이 함께 붙고 스크린리더는
 * 그 이름을 읽는다. 여기 있는 것은 눈으로 보는 사람을 위한 것이다.
 */
public final class NavigationIcons {

    private static final Map<Screen, String> PATHS = new EnumMap<>(Screen.class);

    static {
        PATHS.put(Screen.DASHBOARD, "M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z");
        PATHS.put(Screen.CONNECTION, "M7.5 6h3v2h-3a4 4 0 0 0 0 8h3v2h-3a6 6 0 0 1 0-12h3v2h-3a4 4 0 0 0 0-8zm2.5 5h4v2h-4zm3.5-5h3a6 6 0 1 1 0 12h-3v-2h3a4 4 0 1 0 0-8h-3z");
        PATHS.put(Screen.SEARCH, "M10 3a7 7 0 1 0 4.9 12l5.6 5.5 1.5-1.5-5.5-5.6A7 7 0 0 0 10 3zm0 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z");
        PATHS.put(Screen.STOCK_DETAIL, "M10 3a7 7 0 1 0 4.9 12l5.6 5.5 1.5-1.5-5.5-5.6A7 7 0 0 0 10 3zm0 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z");
        PATHS.put(Screen.WATCHLIST, "M12 2.5l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5-5.8-3-5.8 3 1.1-6.5-4.7-4.6 6.5-.9z");
        // 지갑. 호 명령은 인자가 일곱 개여야 한다 — 예전 경로는 여덟 개라
        // 파싱에 실패해 아이콘이 통째로 비어 있었다.
        PATHS.put(Screen.TRADING, "M4 5h11a3 3 0 0 1 3 3v1h-3a3 3 0 0 0 0 6h3v1a3 3 0 0 1-3 3H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zm11 6h6v3h-6a1.5 1.5 0 0 1 0-3z");
        PATHS.put(Screen.ACCOUNT, "M4 4h16v16H4zm3 4v2h10V8zm0 4v2h10v-2zm0 4v2h6v-2z");
        // 겹친 물결 두 줄. 닮은 모양을 겹쳐 놓았다는 뜻이다.
        PATHS.put(Screen.SIMILAR, "M3 15c3-6 6-6 9 0s6 6 9 0v3c-3 6-6 6-9 0s-6-6-9 0zm0-8c3-6 6-6 9 0s6 6 9 0v3c-3 6-6 6-9 0s-6-6-9 0z");
        // 접힌 신문.
        PATHS.put(Screen.NEWS, "M4 4h13v16H4zm2 3v2h9V7zm0 4v2h9v-2zm0 4v2h6v-2zm13-8h3v11a2 2 0 0 1-4 0V7z");
        PATHS.put(Screen.ANOMALY, "M12 2 1 21h22zm0 5.2-6.2 11.3h12.4zM11 10h2v4h-2zm0 5.5h2v2h-2z");
        PATHS.put(Screen.NOTIFICATIONS, "M12 22a2.5 2.5 0 0 0 2.4-2h-4.8A2.5 2.5 0 0 0 12 22zM20 17H4l2-2v-5a6 6 0 0 1 5-5.9V2h2v2.1A6 6 0 0 1 18 10v5z");
        PATHS.put(Screen.RADIO, "M9 4v12.2a3 3 0 1 0 2 2.8V8h7V4zm-3 16a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm9-2a1 1 0 1 1 0-2 1 1 0 0 1 0 2z");
        PATHS.put(Screen.SETTINGS, "M19.4 13a7.7 7.7 0 0 0 .1-1l2-1.5-2-3.5-2.5 1a8 8 0 0 0-1.7-1L15 4h-4l-.4 3a8 8 0 0 0-1.7 1L6.5 7 4.5 10.5l2 1.5a7.7 7.7 0 0 0 0 2L4.5 15.5 6.5 19 9 18a8 8 0 0 0 1.7 1l.3 3h4l.4-3a8 8 0 0 0 1.7-1l2.5 1 2-3.5zM13 16a4 4 0 1 1 0-8 4 4 0 0 1 0 8z");
    }

    private NavigationIcons() {
    }

    /** 화면의 아이콘 경로. 모든 화면에 하나씩 있어야 한다. */
    public static String pathFor(Screen screen) {
        String path = PATHS.get(screen);
        if (path == null) {
            throw new IllegalStateException(screen + " 아이콘 경로가 없습니다.");
        }
        return path;
    }
}
