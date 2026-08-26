package org.ossproject.desktop;

import javafx.application.Application;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 설치본이 켜지는지를 지키는 검사.
 *
 * <p>실제로 났던 고장이다. jpackage 로 묶은 설치본을 눌러도 창이 뜨지 않았다. 오류
 * 대화상자도 없고 로그도 남지 않았다. 명령줄에서 직접 실행해 보고서야
 * {@code "JavaFX runtime components are missing"} 한 줄을 볼 수 있었다.
 *
 * <p>원인은 실행 클래스가 {@link Application} 을 상속한 것이었다. 자바 11부터 그런
 * 경우 JVM 이 JavaFX 를 <b>모듈 경로</b>에서 먼저 찾고, 없으면 코드를 한 줄도 돌리지
 * 않고 끝낸다. 개발 중에는 {@code org.openjfx.javafxplugin} 이 모듈 경로를 깔아 주므로
 * {@code ./gradlew run} 은 멀쩡히 돈다 — 그래서 배포하기 전까지 아무도 모른다.
 *
 * <p>이 검사가 막으려는 것은 "고쳐 놓은 것을 되돌리는 일" 이다. 누군가 Launcher 를
 * 정리한다며 없애거나 {@code extends Application} 을 붙이면 여기서 걸린다.
 */
class LauncherTest {

    @Test
    @DisplayName("런처는 Application 을 상속하지 않는다")
    void launcherMustNotExtendApplication() {
        assertFalse(Application.class.isAssignableFrom(Launcher.class),
                "런처가 Application 을 상속하면 JVM 이 모듈 경로에서 JavaFX 를 찾다 실패해 "
                        + "설치본이 조용히 죽는다. 감싼 의미가 사라진다.");
    }

    @Test
    @DisplayName("런처에 실행 진입점이 있다")
    void launcherHasAMainMethod() throws NoSuchMethodException {
        var main = Launcher.class.getDeclaredMethod("main", String[].class);

        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());
    }

    /** 감싼 대상은 그대로 Application 이어야 한다. 아니면 감쌀 이유가 없다. */
    @Test
    @DisplayName("실제 화면 클래스는 Application 이다")
    void theWrappedClassIsStillAnApplication() {
        assertTrue(Application.class.isAssignableFrom(DesktopApplication.class));
    }
}
