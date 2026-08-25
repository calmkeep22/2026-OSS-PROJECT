package org.ossproject.desktop.testsupport;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Monocle 헤드리스에서 화면을 그림으로 뽑을 수 있는지 먼저 확인한다. */
@ExtendWith(JavaFxToolkit.class)
public class ScreenshotProbe {

    /**
     * WritableImage 를 PNG 로 쓴다.
     *
     * <p>javafx.swing 의 SwingFXUtils 를 쓰지 않는다. 그 모듈을 넣으려면 앱의 모듈 목록을
     * 늘려야 하는데, 그림을 뽑자고 배포본을 무겁게 할 이유가 없다. 픽셀을 직접 옮긴다.
     */
    public static void write(WritableImage image, File target) throws Exception {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        java.awt.image.BufferedImage buffer = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.setRGB(x, y, pixels.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(buffer, "png", target);
    }

    @Test
    void canSnapshot() throws Exception {
        Path out = Path.of("build", "screens");
        Files.createDirectories(out);
        JavaFxToolkit.onFxThread(() -> {
            Label label = new Label("스냅숏 확인");
            label.setStyle("-fx-font-size: 32px; -fx-padding: 40;");
            StackPane host = new StackPane(label);
            new Scene(host, 400, 200);
            host.applyCss();
            host.layout();
            WritableImage image = host.snapshot(null, null);
            try {
                write(image, new File("build/screens/probe.png"));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertTrue(Files.size(out.resolve("probe.png")) > 0);
        System.out.println("스냅숏 크기 " + Files.size(out.resolve("probe.png")) + " 바이트");
    }
}
