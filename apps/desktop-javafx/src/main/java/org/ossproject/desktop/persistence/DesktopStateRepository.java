package org.ossproject.desktop.persistence;

import java.util.Optional;

public interface DesktopStateRepository {
    Optional<DesktopStateSnapshot> load();
    void save(DesktopStateSnapshot snapshot);
}
