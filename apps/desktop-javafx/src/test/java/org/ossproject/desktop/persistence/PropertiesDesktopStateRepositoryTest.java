package org.ossproject.desktop.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesDesktopStateRepositoryTest {
    @TempDir Path temporaryDirectory;

    @Test void savesAndRestoresTypedUiStateWithoutPlaintextStockData() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        var repository = new PropertiesDesktopStateRepository(file);
        var snapshot = new DesktopStateSnapshot(
                List.of("전체", "반도체"),
                List.of(new WatchlistItem("반도체", "국내", "005930", "삼성전자", "KRX", "KRW", "없음")),
                List.of("삼성전자", "한화"),
                List.of("체결 알림"),
                List.of(new JournalEntry("08/10", "삼성전자", "1", "2", "+1", "메모", "태그")),
                StockSelection.samsungElectronics(), true);

        repository.save(snapshot);

        assertEquals(snapshot, repository.load().orElseThrow());
        assertEquals(List.of("삼성전자", "한화"), repository.load().orElseThrow().recentSearches());
        String stored = Files.readString(file);
        assertTrue(stored.contains("format.version=5"));
        assertFalse(stored.contains("삼성전자"));
        assertFalse(stored.toLowerCase().contains("app secret"));
    }

    @Test void migratesVersionOneStringRowsToTypedRecords() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        String legacy = "format.version=1\n"
                + "watchlist.groups=" + encoded("전체", "반도체") + "\n"
                + "watchlist.rows=" + encoded("반도체", "삼성전자", "72,500원", "+2.1%", "100", "없음") + "\n"
                + "alert.rules=" + encoded("삼성전자", "가격 이상", "75,000원", "활성") + "\n"
                + "notifications=" + encoded("체결 알림") + "\n"
                + "journal.rows=" + encoded("08/10", "삼성전자", "1", "2", "+1", "메모", "태그") + "\n"
                + "selected.stock=" + encoded("국내", "005930", "삼성전자", "KRX", "72,500원", "+2.1%") + "\n"
                + "setting.preventDuplicateOrders=true\nsetting.maxSubscriptions=160\n";
        Files.writeString(file, legacy, StandardCharsets.ISO_8859_1);

        DesktopStateSnapshot restored = new PropertiesDesktopStateRepository(file).load().orElseThrow();

        assertEquals("삼성전자", restored.watchlistItems().get(0).securityName());
        assertTrue(restored.watchlistItems().get(0).needsIdentityRepair());
        assertTrue(restored.recentSearches().isEmpty());
        assertEquals("메모", restored.journalEntries().get(0).memo());
    }

    @Test void dropsStoredPricesFromOlderSelectionsAndKeepsIdentity() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        String legacy = "format.version=2\n"
                + "selected.stock=" + encoded("미국", "AAPL", "Apple", "NASDAQ", "$228.40", "+0.83%") + "\n";
        Files.writeString(file, legacy, StandardCharsets.ISO_8859_1);

        StockSelection restored = new PropertiesDesktopStateRepository(file).load().orElseThrow().selectedStock();

        assertEquals("AAPL", restored.symbol());
        assertEquals("NASDAQ", restored.exchange());
        assertEquals("USD", restored.currency());
        assertTrue(restored.overseas());
    }

    @Test void skipsMalformedTypedRowsButKeepsValidRows() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        String rows = encoded("반도체", "삼성전자", "72,500원", "+2.1%", "100", "없음")
                + ";" + encoded("열", "부족");
        Files.writeString(file, "watchlist.rows=" + rows + "\n", StandardCharsets.ISO_8859_1);

        DesktopStateSnapshot restored = new PropertiesDesktopStateRepository(file).load().orElseThrow();

        assertEquals(1, restored.watchlistItems().size());
    }

    @Test void corruptedFileFallsBackToEmpty() throws Exception {
        Path file = temporaryDirectory.resolve("ui-state.properties");
        Files.writeString(file, "watchlist.groups=not-valid-base64%%%\n");
        assertTrue(new PropertiesDesktopStateRepository(file).load().isEmpty());
    }

    private static String encoded(String... values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return java.util.Arrays.stream(values)
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + "," + right).orElse("");
    }
}
