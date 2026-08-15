package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.SonificationPreferences;
import org.ossproject.sonification.model.GraphScaleMode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public final class PropertiesSonificationPreferencesRepository implements SonificationPreferencesRepository {
    private final Path file;

    public PropertiesSonificationPreferencesRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override public SonificationPreferences load() {
        return AtomicPropertiesFile.load(file).map(this::decode).orElse(SonificationPreferences.DEFAULT);
    }

    @Override public void save(SonificationPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        properties.setProperty("scale.mode", preferences.scaleMode().name());
        properties.setProperty("scale.percentRange", Double.toString(preferences.percentRange()));
        properties.setProperty("playback.speed", Double.toString(preferences.playbackSpeed()));
        properties.setProperty("audio.volume", Double.toString(preferences.volume()));
        AtomicPropertiesFile.save(file, properties, "OpenStock Access sonification preferences - no credentials");
    }

    private SonificationPreferences decode(Properties properties) {
        SonificationPreferences defaults = SonificationPreferences.DEFAULT;
        return new SonificationPreferences(
                scaleMode(properties.getProperty("scale.mode"), defaults.scaleMode()),
                decimal(properties, "scale.percentRange", defaults.percentRange()),
                decimal(properties, "playback.speed", defaults.playbackSpeed()),
                decimal(properties, "audio.volume", defaults.volume()));
    }

    private GraphScaleMode scaleMode(String value, GraphScaleMode fallback) {
        try { return GraphScaleMode.valueOf(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private double decimal(Properties properties, String key, double fallback) {
        try { return Double.parseDouble(properties.getProperty(key, Double.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
