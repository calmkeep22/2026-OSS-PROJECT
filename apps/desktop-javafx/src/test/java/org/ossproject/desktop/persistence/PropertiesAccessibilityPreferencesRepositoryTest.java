package org.ossproject.desktop.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.desktop.state.AccessibilityPreferences;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesAccessibilityPreferencesRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void savesAndLoadsAccessibilityPreferences() throws Exception {
        Path file = temporaryDirectory.resolve("accessibility.properties");
        var repository = new PropertiesAccessibilityPreferencesRepository(file);
        var preferences = new AccessibilityPreferences(
                true, false, true, true, true, true, "넓게", "Microsoft Heami", 1.25, 75);

        repository.save(preferences);

        assertEquals(preferences, repository.load());
        String stored = Files.readString(file).toLowerCase();
        assertFalse(stored.contains("token"));
        assertFalse(stored.contains("account"));
    }

    @Test void missingOrMalformedValuesUseSafeDefaultsAndRanges() throws Exception {
        Path file = temporaryDirectory.resolve("accessibility.properties");
        Files.writeString(file, "speech.enabled=invalid\nspeech.rate=999\nspeech.volume=-20\ninformation.density=unknown\n");

        AccessibilityPreferences loaded = new PropertiesAccessibilityPreferencesRepository(file).load();

        assertEquals(AccessibilityPreferences.DEFAULT.speechEnabled(), loaded.speechEnabled());
        assertEquals(2.0, loaded.speechRate());
        assertEquals(0, loaded.speechVolume());
        assertEquals("표준", loaded.informationDensity());
    }

    @Test void unreadablePropertiesReturnDefaults() throws Exception {
        Path file = temporaryDirectory.resolve("accessibility.properties");
        Files.writeString(file, "bad=\\uZZZZ\n");
        assertEquals(AccessibilityPreferences.DEFAULT,
                new PropertiesAccessibilityPreferencesRepository(file).load());
    }

    @Test void migratesAccessibilityValuesFromLegacyUiState() throws Exception {
        Path current = temporaryDirectory.resolve("accessibility.properties");
        Path legacy = temporaryDirectory.resolve("ui-state.properties");
        Files.writeString(legacy, "setting.speech=true\nsetting.sound=false\nsetting.keyboard=false\n"
                + "setting.reducedMotion=true\nsetting.largeText=false\nsetting.highContrast=true\n"
                + "setting.density=자세히\nsetting.voice=Legacy Voice\nsetting.speechRate=1.4\n"
                + "setting.speechVolume=65\n");

        AccessibilityPreferences loaded =
                new PropertiesAccessibilityPreferencesRepository(current, legacy).load();

        assertTrue(loaded.speechEnabled());
        assertFalse(loaded.soundEnabled());
        assertTrue(loaded.highContrastEnabled());
        assertEquals("넓게", loaded.informationDensity());
        assertEquals("Legacy Voice", loaded.voiceName());
        assertEquals(65, loaded.speechVolume());
    }
}
