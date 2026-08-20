package org.ossproject.desktop.testsupport;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(JavaFxToolkit.class)
class JavaFxToolkitTest {

    /** 툴킷이 없으면 컨트롤 생성부터 IllegalStateException 이 난다. */
    @Test void createsControlsThatUsedToNeedADisplay() {
        JavaFxToolkit.onFxThread(() -> {
            Label label = new Label("체결");
            Button button = new Button("주문");

            assertEquals("체결", label.getText());
            assertEquals("주문", button.getText());
        });
    }

    /** 단추를 눌러 동작이 실제로 도는지 확인할 수 있어야 한다. */
    @Test void firesButtonActions() {
        AtomicInteger clicks = new AtomicInteger();
        JavaFxToolkit.onFxThread(() -> {
            Button button = new Button("다시 시도");
            button.setOnAction(event -> clicks.incrementAndGet());

            button.fire();
        });

        assertEquals(1, clicks.get());
    }

    /** 안에서 실패한 단언이 통과로 보이면 안 된다. */
    @Test void surfacesFailuresFromTheFxThread() {
        assertThrows(AssertionError.class,
                () -> JavaFxToolkit.onFxThread(() -> fail("화면 스레드에서 실패")));
    }
}
