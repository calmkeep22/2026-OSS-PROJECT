package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.AccessibilityPreferences;

public interface AccessibilityPreferencesRepository {
    AccessibilityPreferences load();
    void save(AccessibilityPreferences preferences);
}
