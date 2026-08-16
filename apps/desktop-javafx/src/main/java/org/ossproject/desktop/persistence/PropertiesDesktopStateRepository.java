package org.ossproject.desktop.persistence;

import org.ossproject.desktop.state.AlertRule;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/** 데스크톱 화면 상태를 사용자 로컬 properties 파일에 원자적으로 저장한다. */
public final class PropertiesDesktopStateRepository implements DesktopStateRepository {
    private static final String FORMAT_VERSION = "3";
    private final Path file;

    public PropertiesDesktopStateRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    @Override public Optional<DesktopStateSnapshot> load() {
        return AtomicPropertiesFile.load(file).flatMap(this::decode);
    }

    @Override public void save(DesktopStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Properties properties = new Properties();
        properties.setProperty("format.version", FORMAT_VERSION);
        properties.setProperty("watchlist.groups", encodeList(snapshot.watchlistGroups()));
        properties.setProperty("watchlist.rows", encodeRows(snapshot.watchlistItems(), item -> List.of(
                item.group(), item.securityName(), item.displayPrice(), item.displayChange(),
                item.displayVolume(), item.alertText())));
        properties.setProperty("alert.rules", encodeRows(snapshot.alertRules(), rule -> List.of(
                rule.securityName(), rule.condition(), rule.threshold(), Boolean.toString(rule.enabled()))));
        properties.setProperty("notifications", encodeList(snapshot.notifications()));
        properties.setProperty("journal.rows", encodeRows(snapshot.journalEntries(), entry -> List.of(
                entry.date(), entry.securityName(), entry.buyAmount(), entry.sellAmount(), entry.profitLoss(),
                entry.memo(), entry.tags())));
        properties.setProperty("selected.stock", encodeList(List.of(
                snapshot.selectedStock().market(), snapshot.selectedStock().symbol(), snapshot.selectedStock().name(),
                snapshot.selectedStock().exchange(), snapshot.selectedStock().currency())));
        properties.setProperty("setting.preventDuplicateOrders", Boolean.toString(snapshot.preventDuplicateOrders()));
        properties.setProperty("setting.maxSubscriptions", Integer.toString(snapshot.maxSubscriptions()));
        AtomicPropertiesFile.save(file, properties, "OpenStock Access UI state - no credentials");
    }

    private Optional<DesktopStateSnapshot> decode(Properties properties) {
        try {
            return Optional.of(new DesktopStateSnapshot(
                    decodeList(properties.getProperty("watchlist.groups")),
                    decodeRows(properties.getProperty("watchlist.rows"), this::watchlistItem),
                    decodeRows(properties.getProperty("alert.rules"), this::alertRule),
                    decodeList(properties.getProperty("notifications")),
                    decodeRows(properties.getProperty("journal.rows"), this::journalEntry),
                    decodeSelection(properties.getProperty("selected.stock")),
                    bool(properties, "setting.preventDuplicateOrders", true),
                    integer(properties, "setting.maxSubscriptions", 160)));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private Optional<WatchlistItem> watchlistItem(List<String> fields) {
        if (fields.size() != 6) return Optional.empty();
        return safe(() -> new WatchlistItem(
                fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4), fields.get(5)));
    }

    private Optional<AlertRule> alertRule(List<String> fields) {
        if (fields.size() != 4) return Optional.empty();
        boolean enabled = "true".equalsIgnoreCase(fields.get(3)) || "활성".equals(fields.get(3));
        return safe(() -> new AlertRule(fields.get(0), fields.get(1), fields.get(2), enabled));
    }

    private Optional<JournalEntry> journalEntry(List<String> fields) {
        if (fields.size() != 7) return Optional.empty();
        return safe(() -> new JournalEntry(
                fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4), fields.get(5), fields.get(6)));
    }

    private <T> Optional<T> safe(java.util.function.Supplier<T> supplier) {
        try { return Optional.of(supplier.get()); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
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

    private <T> String encodeRows(List<T> rows, Function<T, List<String>> mapper) {
        return rows.stream().map(mapper).map(this::encodeList)
                .reduce((left, right) -> left + ";" + right).orElse("");
    }

    private <T> List<T> decodeRows(String value, Function<List<String>, Optional<T>> mapper) {
        if (value == null || value.isBlank()) return List.of();
        List<T> decoded = new ArrayList<>();
        for (String encodedRow : value.split(";", -1)) {
            mapper.apply(decodeList(encodedRow)).ifPresent(decoded::add);
        }
        return List.copyOf(decoded);
    }

    private String encodeList(List<String> values) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return values.stream()
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + "," + right).orElse("");
    }

    private List<String> decodeList(String value) {
        if (value == null || value.isBlank()) return List.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        return Arrays.stream(value.split(",", -1))
                .map(encoded -> new String(decoder.decode(encoded), StandardCharsets.UTF_8)).toList();
    }

    /**
     * 선택 종목을 읽는다.
     *
     * <p>format 2까지는 화면 표기 가격을 함께 저장했다. 그 파일을 읽을 때는 앞의 식별 정보만
     * 쓰고 통화는 거래소에서 되살린다. 가격은 저장하지 않고 항상 다시 조회한다.
     */
    private StockSelection decodeSelection(String value) {
        List<String> fields = decodeList(value);
        if (fields.size() == 5) {
            return safe(() -> new StockSelection(fields.get(0), fields.get(1), fields.get(2),
                    fields.get(3), fields.get(4))).orElseGet(StockSelection::samsungElectronics);
        }
        if (fields.size() == 6) {
            return safe(() -> new StockSelection(fields.get(0), fields.get(1), fields.get(2),
                    fields.get(3), currencyOf(fields.get(0), fields.get(3))))
                    .orElseGet(StockSelection::samsungElectronics);
        }
        return StockSelection.samsungElectronics();
    }

    private static String currencyOf(String market, String exchange) {
        return "미국".equals(market) || "NASDAQ".equalsIgnoreCase(exchange)
                || "NYSE".equalsIgnoreCase(exchange) ? "USD" : "KRW";
    }
}
