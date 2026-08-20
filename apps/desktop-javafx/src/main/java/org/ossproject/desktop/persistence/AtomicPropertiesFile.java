package org.ossproject.desktop.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/** properties 파일의 안전한 읽기와 원자적 교체를 한곳에서 보장한다. */
final class AtomicPropertiesFile {
    private AtomicPropertiesFile() {}

    static Optional<Properties> load(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return Optional.of(properties);
        } catch (IOException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    static void save(Path file, Properties properties, String comment) {
        Path temporary = null;
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, comment);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
            throw new IllegalStateException("설정 파일을 저장하지 못했습니다: " + file, error);
        }
    }
}
