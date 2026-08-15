package org.ossproject.desktop.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.desktop.state.SonificationPreferences;
import org.ossproject.sonification.model.GraphScaleMode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropertiesSonificationPreferencesRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void savesAndLoadsChartPlaybackPreferences() {
        Path file = temporaryDirectory.resolve("sonification.properties");
        var repository = new PropertiesSonificationPreferencesRepository(file);
        var preferences = new SonificationPreferences(GraphScaleMode.PERCENT_FROM_REFERENCE, 8.0, 1.5, 0.65);

        repository.save(preferences);

        assertEquals(preferences, repository.load());
    }

    @Test void malformedValuesReturnSafeDefaultsAndClampedRanges() throws Exception {
        Path file = temporaryDirectory.resolve("sonification.properties");
        Files.writeString(file, "scale.mode=UNKNOWN\nscale.percentRange=-5\nplayback.speed=99\naudio.volume=NaN\n");

        SonificationPreferences loaded = new PropertiesSonificationPreferencesRepository(file).load();

        assertEquals(GraphScaleMode.AUTOMATIC, loaded.scaleMode());
        assertEquals(0.1, loaded.percentRange());
        assertEquals(4.0, loaded.playbackSpeed());
        assertEquals(0.8, loaded.volume());
    }
}
