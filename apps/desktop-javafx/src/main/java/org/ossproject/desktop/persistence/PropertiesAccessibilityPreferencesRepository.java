package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.AccessibilityPreferences;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

public final class PropertiesAccessibilityPreferencesRepository implements AccessibilityPreferencesRepository {
    private final Path file;
    private final Path legacyStateFile;

    public PropertiesAccessibilityPreferencesRepository(Path file) {
        this(file, null);
    }

    public PropertiesAccessibilityPreferencesRepository(Path file, Path legacyStateFile) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.legacyStateFile = legacyStateFile == null ? null : legacyStateFile.toAbsolutePath().normalize();
    }

    @Override public AccessibilityPreferences load() {
        Optional<Properties> current = AtomicPropertiesFile.load(file);
        if (current.isPresent()) return decode(current.get());
        if (legacyStateFile != null) {
            return AtomicPropertiesFile.load(legacyStateFile)
                    .map(this::decodeLegacy).orElse(AccessibilityPreferences.DEFAULT);
        }
        return AccessibilityPreferences.DEFAULT;
    }

    @Override public void save(AccessibilityPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        properties.setProperty("speech.enabled", Boolean.toString(preferences.speechEnabled()));
        properties.setProperty("sound.enabled", Boolean.toString(preferences.soundEnabled()));
        properties.setProperty("keyboard.guidance", Boolean.toString(preferences.keyboardGuidanceEnabled()));
        properties.setProperty("motion.reduced", Boolean.toString(preferences.reducedMotionEnabled()));
        properties.setProperty("text.large", Boolean.toString(preferences.largeTextEnabled()));
        properties.setProperty("contrast.high", Boolean.toString(preferences.highContrastEnabled()));
        properties.setProperty("information.density", preferences.informationDensity());
        properties.setProperty("speech.voice", preferences.voiceName());
        properties.setProperty("speech.rate", Double.toString(preferences.speechRate()));
        properties.setProperty("speech.volume", Integer.toString(preferences.speechVolume()));
        AtomicPropertiesFile.save(file, properties, "OpenStock Access accessibility preferences - no credentials");
    }

    private AccessibilityPreferences decode(Properties properties) {
        AccessibilityPreferences defaults = AccessibilityPreferences.DEFAULT;
        return new AccessibilityPreferences(
                bool(properties, "speech.enabled", defaults.speechEnabled()),
                bool(properties, "sound.enabled", defaults.soundEnabled()),
                bool(properties, "keyboard.guidance", defaults.keyboardGuidanceEnabled()),
                bool(properties, "motion.reduced", defaults.reducedMotionEnabled()),
                bool(properties, "text.large", defaults.largeTextEnabled()),
                bool(properties, "contrast.high", defaults.highContrastEnabled()),
                properties.getProperty("information.density", defaults.informationDensity()),
                properties.getProperty("speech.voice", defaults.voiceName()),
                decimal(properties, "speech.rate", defaults.speechRate()),
                integer(properties, "speech.volume", defaults.speechVolume()));
    }

    private AccessibilityPreferences decodeLegacy(Properties properties) {
        AccessibilityPreferences defaults = AccessibilityPreferences.DEFAULT;
        return new AccessibilityPreferences(
                bool(properties, "setting.speech", defaults.speechEnabled()),
                bool(properties, "setting.sound", defaults.soundEnabled()),
                bool(properties, "setting.keyboard", defaults.keyboardGuidanceEnabled()),
                bool(properties, "setting.reducedMotion", defaults.reducedMotionEnabled()),
                bool(properties, "setting.largeText", defaults.largeTextEnabled()),
                bool(properties, "setting.highContrast", defaults.highContrastEnabled()),
                properties.getProperty("setting.density", defaults.informationDensity()),
                properties.getProperty("setting.voice", defaults.voiceName()),
                decimal(properties, "setting.speechRate", defaults.speechRate()),
                integer(properties, "setting.speechVolume", defaults.speechVolume()));
    }

    private boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                ? Boolean.parseBoolean(value) : fallback;
    }

    private int integer(Properties properties, String key, int fallback) {
        try { return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private double decimal(Properties properties, String key, double fallback) {
        try { return Double.parseDouble(properties.getProperty(key, Double.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
