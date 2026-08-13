package org.ossproject.desktop.persistence;

import org.ossproject.desktop.viewmodel.StockSelection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * UI 상태를 사용자 로컬 properties 파일에 원자적으로 저장한다.
 * App Key·Secret·토큰은 이 저장소에 절대 기록하지 않는다.
 */
public final class PropertiesDesktopStateRepository implements DesktopStateRepository {
    private final Path file;

    public PropertiesDesktopStateRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override public Optional<DesktopStateSnapshot> load() {
        if (!Files.isRegularFile(file)) return Optional.empty();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            return Optional.of(new DesktopStateSnapshot(
                    decodeList(properties.getProperty("watchlist.groups")),
                    decodeRows(properties.getProperty("watchlist.rows")),
                    decodeRows(properties.getProperty("alert.rules")),
                    decodeList(properties.getProperty("notifications")),
                    decodeRows(properties.getProperty("journal.rows")),
                    decodeSelection(properties.getProperty("selected.stock")),
                    bool(properties, "setting.speech", false),
                    bool(properties, "setting.sound", true),
                    bool(properties, "setting.keyboard", true),
                    bool(properties, "setting.reducedMotion", true),
                    bool(properties, "setting.largeText", true),
                    bool(properties, "setting.highContrast", false),
                    properties.getProperty("setting.density", "표준"),
                    properties.getProperty("setting.voice", ""),
                    decimal(properties, "setting.speechRate", 1.0),
                    integer(properties, "setting.speechVolume", 100),
                    bool(properties, "setting.preventDuplicateOrders", true),
                    integer(properties, "setting.maxSubscriptions", 160)));
        } catch (RuntimeException | IOException invalid) {
            return Optional.empty();
        }
    }

    @Override public void save(DesktopStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        properties.setProperty("watchlist.groups", encodeList(snapshot.watchlistGroups()));
        properties.setProperty("watchlist.rows", encodeRows(snapshot.watchlistRows()));
        properties.setProperty("alert.rules", encodeRows(snapshot.alertRules()));
        properties.setProperty("notifications", encodeList(snapshot.notifications()));
        properties.setProperty("journal.rows", encodeRows(snapshot.journalRows()));
        properties.setProperty("selected.stock", encodeList(List.of(
                snapshot.selectedStock().market(), snapshot.selectedStock().symbol(), snapshot.selectedStock().name(),
                snapshot.selectedStock().exchange(), snapshot.selectedStock().displayPrice(), snapshot.selectedStock().displayChange())));
        properties.setProperty("setting.speech", Boolean.toString(snapshot.speechEnabled()));
        properties.setProperty("setting.sound", Boolean.toString(snapshot.soundEnabled()));
        properties.setProperty("setting.keyboard", Boolean.toString(snapshot.keyboardGuidanceEnabled()));
        properties.setProperty("setting.reducedMotion", Boolean.toString(snapshot.reducedMotionEnabled()));
        properties.setProperty("setting.largeText", Boolean.toString(snapshot.largeTextEnabled()));
        properties.setProperty("setting.highContrast", Boolean.toString(snapshot.highContrastEnabled()));
        properties.setProperty("setting.density", snapshot.informationDensity());
        properties.setProperty("setting.voice", snapshot.voiceName());
        properties.setProperty("setting.speechRate", Double.toString(snapshot.speechRate()));
        properties.setProperty("setting.speechVolume", Integer.toString(snapshot.speechVolume()));
        properties.setProperty("setting.preventDuplicateOrders", Boolean.toString(snapshot.preventDuplicateOrders()));
        properties.setProperty("setting.maxSubscriptions", Integer.toString(snapshot.maxSubscriptions()));
        try {
            Path parent = file.getParent(); if (parent != null) Files.createDirectories(parent);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "OpenStock Access UI state - no credentials");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("UI 상태를 저장하지 못했습니다: " + file, error);
        }
    }

    private boolean bool(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)));
    }

    private int integer(Properties properties, String key, int fallback) {
        try { return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private double decimal(Properties properties, String key, double fallback) {
        try { return Double.parseDouble(properties.getProperty(key, Double.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String encodeRows(List<List<String>> rows) {
        return rows.stream().map(this::encodeList).reduce((left, right) -> left + ";" + right).orElse("");
    }

    private List<List<String>> decodeRows(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(";", -1)).map(this::decodeList).toList();
    }

    private String encodeList(List<String> values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return values.stream().map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    private List<String> decodeList(String value) {
        if (value == null || value.isBlank()) return List.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return Arrays.stream(value.split(",", -1))
                .map(encoded -> new String(decoder.decode(encoded), StandardCharsets.UTF_8)).toList();
    }

    private StockSelection decodeSelection(String value) {
        List<String> fields = decodeList(value);
        return fields.size() == 6 ? new StockSelection(fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4), fields.get(5))
                : StockSelection.samsungElectronics();
    }
}
