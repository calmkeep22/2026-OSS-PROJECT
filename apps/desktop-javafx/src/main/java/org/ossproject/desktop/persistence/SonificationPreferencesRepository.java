package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.SonificationPreferences;

public interface SonificationPreferencesRepository {
    SonificationPreferences load();
    void save(SonificationPreferences preferences);
}
