package org.ossproject.desktop.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesDesktopStateRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void savesAndRestoresUiStateWithoutPlaintextStockData() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        var repository = new PropertiesDesktopStateRepository(file);
        var snapshot = new DesktopStateSnapshot(
                List.of("전체", "반도체"), List.of(List.of("반도체", "삼성전자", "72,500원", "+2.1%", "100", "없음")),
                List.of(List.of("삼성전자", "가격 이상", "75,000원", "활성")), List.of("체결 알림"),
                List.of(List.of("08/10", "삼성전자", "1", "2", "+1", "메모", "태그")),
                StockSelection.samsungElectronics(), true, true, true, true, true, false,
                "표준", "Microsoft Heami Desktop", 1.2, 80, true, 160);

        repository.save(snapshot);

        assertEquals(snapshot, repository.load().orElseThrow());
        String stored = Files.readString(file);
        assertFalse(stored.contains("삼성전자"));
        assertFalse(stored.toLowerCase().contains("app secret"));
    }

    @Test void corruptedFileFallsBackToEmpty() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        Files.writeString(file, "watchlist.groups=not-valid-base64%%%\n");
        assertTrue(new PropertiesDesktopStateRepository(file).load().isEmpty());
    }
}
