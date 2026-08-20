package org.ossproject.desktop.testsupport;

import javafx.application.Platform;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트에서 JavaFX 툴킷을 한 번만 띄운다.
 *
 * <p>{@code Button} 이나 {@code Label} 같은 컨트롤은 툴킷 없이 만들 수 없고,
 * {@code Platform.runLater} 도 마찬가지다. 그래서 화면 계층은 오랫동안 검증되지 않았다.
 * Monocle 로 표시 장치 없이 띄우면 CI 에서도 돌릴 수 있다.
 *
 * <p>{@link Platform#startup} 은 두 번 부르면 예외가 난다. 확장은 클래스마다 실행되므로
 * 한 번만 부르도록 잠근다.
 *
 * <pre>{@code
 * @ExtendWith(JavaFxToolkit.class)
 * class SomeViewTest {
 *     @Test void something() {
 *         JavaFxToolkit.onFxThread(() -> { ... });
 *     }
 * }
 * }</pre>
 */
public final class JavaFxToolkit implements BeforeAllCallback {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long TIMEOUT_SECONDS = 20;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        start();
    }

    /** 툴킷을 띄운다. 이미 떠 있으면 아무 일도 하지 않는다. */
    public static void start() throws InterruptedException {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        if (!ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX 툴킷이 " + TIMEOUT_SECONDS + "초 안에 뜨지 않았습니다.");
        }
        // 테스트가 끝나도 툴킷을 내리지 않는다. 다시 띄울 수 없기 때문이다.
        Platform.setImplicitExit(false);
    }

    /**
     * 화면 스레드에서 실행하고 끝날 때까지 기다린다.
     *
     * <p>안에서 난 예외는 호출한 쪽으로 그대로 올린다. 삼키면 실패한 단언이 통과로 보인다.
     */
    public static void onFxThread(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("화면 스레드 작업이 끝나지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("화면 스레드 작업을 기다리다 중단되었습니다.", e);
        }
        Throwable thrown = failure.get();
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new IllegalStateException(thrown);
        }
    }
}
