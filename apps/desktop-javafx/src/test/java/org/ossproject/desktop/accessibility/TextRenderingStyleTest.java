package org.ossproject.desktop.accessibility;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 화면 배율과 캡처 방식에 따라 한글 가장자리가 RGB 로 갈라지지 않는지 검사한다. */
@ExtendWith(JavaFxToolkit.class)
class TextRenderingStyleTest {

    @Test
    @DisplayName("앱의 글자는 회색조 안티앨리어싱으로 렌더링한다")
    void usesGrayscaleFontSmoothing() {
        JavaFxToolkit.onFxThread(() -> {
            Label label = new Label("시세를 소리와 글자로 탐색");
            StackPane root = new StackPane(label);
            root.getStyleClass().add("app-root");
            Scene scene = new Scene(root, 500, 120);
            URL stylesheet = TextRenderingStyleTest.class.getResource("/styles/application.css");
            assertNotNull(stylesheet);
            scene.getStylesheets().add(stylesheet.toExternalForm());

            root.applyCss();
            Text renderedText = (Text) label.lookup(".text");

            assertNotNull(renderedText);
            assertEquals(FontSmoothingType.GRAY, renderedText.getFontSmoothingType());
        });
    }
}
