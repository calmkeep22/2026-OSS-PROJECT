package org.ossproject.desktop;

/**
 * 배포본이 실제로 켜지게 하는 진입점.
 *
 * <p>이 클래스가 하는 일은 없다. 그런데 없으면 설치본이 창을 띄우지 못하고 조용히
 * 죽는다.
 *
 * <p>자바 11부터 실행 클래스가 {@code javafx.application.Application} 을 상속하면,
 * JVM 이 시작하기 전에 JavaFX 가 <b>모듈 경로</b>에 있는지 먼저 확인한다. 없으면
 * 코드를 한 줄도 돌리지 않고 "JavaFX runtime components are missing" 만 남기고
 * 끝낸다. 개발 중에는 {@code org.openjfx.javafxplugin} 이 모듈 경로를 깔아 주므로
 * {@code ./gradlew run} 은 멀쩡히 돌고, 그래서 이 고장은 jpackage 로 묶은 뒤에야
 * 드러난다 — 창이 안 뜨는데 오류 대화상자도 없고 로그도 남지 않는다.
 *
 * <p>검사는 "실행 클래스가 Application 을 상속했는가" 만 본다. 상속하지 않은 클래스로
 * 한 번 감싸면 검사를 지나가고, 그 뒤로는 클래스패스에 있는 JavaFX 가 평소대로 쓰인다.
 *
 * <p>그러므로 <b>이 클래스는 절대 {@code Application} 을 상속하면 안 된다.</b>
 * 상속하는 순간 감싼 의미가 사라지고 배포본이 다시 안 켜진다.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        DesktopApplication.main(args);
    }
}
