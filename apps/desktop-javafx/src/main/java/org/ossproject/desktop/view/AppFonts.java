package org.ossproject.desktop.view;

import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.List;

/**
 * 앱이 실제로 쓸 글꼴을 정한다.
 *
 * <p>CSS 에만 이름을 적어 두면 그 글꼴이 깔린 컴퓨터에서만 나온다. 실제로 그랬다 —
 * 개발 컴퓨터에는 Pretendard 가 없어 화면이 JavaFX 기본 글꼴로 그려지고 있었고,
 * 어느 컴퓨터에서 여느냐에 따라 글자 모양이 달라졌다.
 *
 * <p>JavaFX 의 {@code -fx-font-family} 는 웹 CSS 처럼 쉼표로 대안을 늘어놓는 문법을
 * 받지 않는다. 측정해 보니 목록을 주면 통째로 무시하고 기본 글꼴로 떨어졌다. 그래서
 * 대안 고르기는 CSS 가 아니라 여기서 한다 — 실제로 쓸 수 있는 이름 하나를 골라
 * 뿌리에 직접 건다.
 *
 * <p>글꼴 파일이 없어도 앱은 그대로 뜬다. 글자 모양은 이 앱의 부가 요소가 아니지만,
 * 그렇다고 파일 하나 때문에 거래를 못 하게 만들 이유도 없다.
 */
public final class AppFonts {

    /** 번들한 글꼴. 굵기별로 따로 등록해야 굵은 글씨가 가짜로 부풀지 않는다. */
    private static final List<String> BUNDLED = List.of(
            "/fonts/Pretendard-Regular.otf",
            "/fonts/Pretendard-Medium.otf",
            "/fonts/Pretendard-SemiBold.otf",
            "/fonts/Pretendard-Bold.otf");

    private static final String PREFERRED = "Pretendard";

    /**
     * 대안. 번들 글꼴을 못 읽었을 때 쓴다.
     *
     * <p>Noto Sans KR 은 뺐다. 윈도우에 깔려 있어도 가변 글꼴(NotoSansKR-VF.ttf)이라
     * JavaFX 17 이 목록에 올리지 못한다. 있는 것처럼 적어 두면 다시 기본 글꼴로 떨어진다.
     */
    private static final List<String> FALLBACKS = List.of("Malgun Gothic", "Segoe UI");

    private AppFonts() {
    }

    /**
     * 번들 글꼴을 등록하고, 화면에 걸 글꼴 이름을 돌려준다.
     *
     * @return 실제로 쓸 수 있는 글꼴 이름. 아무것도 못 찾으면 빈 문자열이고,
     *         그때는 뿌리에 아무것도 걸지 않아 JavaFX 기본값이 쓰인다
     */
    public static String install() {
        for (String path : BUNDLED) {
            load(path);
        }
        if (Font.getFamilies().contains(PREFERRED)) {
            return PREFERRED;
        }
        for (String fallback : FALLBACKS) {
            if (Font.getFamilies().contains(fallback)) {
                return fallback;
            }
        }
        return "";
    }

    /** 뿌리에 걸 인라인 스타일. 이름이 없으면 빈 문자열이라 아무 일도 하지 않는다. */
    public static String rootStyle(String family) {
        return family.isBlank() ? "" : "-fx-font-family: \"" + family + "\";";
    }

    private static void load(String path) {
        try (InputStream stream = AppFonts.class.getResourceAsStream(path)) {
            if (stream == null) {
                return;
            }
            // 크기는 여기서 정하지 않는다. CSS 의 -fx-font-size 가 정한다.
            Font.loadFont(stream, 12);
        } catch (Exception ignored) {
            // 글꼴 하나를 못 읽는다고 앱이 안 뜨면 안 된다. 나머지 굵기로 그린다.
        }
    }
}
