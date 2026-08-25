package org.ossproject.desktop.view.screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(JavaFxToolkit.class)
class NotificationsScreenViewTest {

    @Test
    @DisplayName("알림 목록에서 듣기·읽음·삭제를 키보드로 실행한다")
    void supportsListKeyboardActions() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<String> entries = FXCollections.observableArrayList(
                    "새 알림 · 10:00 · 가격 · 삼성전자 목표 가격에 도달했습니다.");
            AtomicReference<String> spoken = new AtomicReference<>();
            ScrollPane root = new NotificationsScreenView(entries, ignored -> { }, () -> { },
                    (text, id) -> spoken.set(text)).create();
            ListView<?> list = findList(root);
            assertNotNull(list);

            list.fireEvent(key(KeyCode.ENTER, false));
            assertEquals(entries.get(0), spoken.get());

            list.fireEvent(key(KeyCode.ENTER, true));
            assertEquals("10:00 · 가격 · 삼성전자 목표 가격에 도달했습니다.", entries.get(0));

            list.getSelectionModel().selectFirst();
            list.fireEvent(key(KeyCode.DELETE, false));
            assertEquals(0, entries.size());
        });
    }

    private static KeyEvent key(KeyCode code, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                false, control, false, false);
    }

    private static ListView<?> findList(Node node) {
        if (node instanceof ListView<?> list) return list;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            ListView<?> found = findList(scroll.getContent());
            if (found != null) return found;
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ListView<?> found = findList(child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
