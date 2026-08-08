package org.ossproject.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.anomaly.AnomalyAlert;
import org.ossproject.anomaly.AnomalySeverity;
import org.ossproject.anomaly.AnomalyType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAnomalyAlertRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:00:00Z");

    private SqliteDatabase database;
    private SqliteAnomalyAlertRepository repository;

    @BeforeEach
    void setUp() {
        database = SqliteDatabase.openInMemory();
        repository = new SqliteAnomalyAlertRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private AnomalyAlert alert(String symbol, AnomalyType type, Instant detectedAt) {
        return new AnomalyAlert(symbol, "삼성전자", type, AnomalySeverity.HIGH,
                new BigDecimal("4.25"), "직전 거래일 대비 가격 4.25% 상승", detectedAt);
    }

    @Test
    @DisplayName("알림을 저장하고 최근 순으로 읽어 온다")
    void savesAndReadsRecent() {
        repository.save(alert("005930", AnomalyType.PRICE_SURGE, NOW.minusSeconds(60)));
        repository.save(alert("000660", AnomalyType.VOLUME_SPIKE, NOW));

        List<AnomalyAlert> recent = repository.findRecent(10);

        assertEquals(2, recent.size());
        assertEquals("000660", recent.get(0).symbol());
        assertEquals(AnomalyType.VOLUME_SPIKE, recent.get(0).type());
    }

    @Test
    @DisplayName("소수점 값이 그대로 보존된다")
    void preservesRatio() {
        repository.save(alert("005930", AnomalyType.PRICE_SURGE, NOW));

        assertEquals("4.25", repository.findRecent(1).get(0).observedRatio().toPlainString());
    }

    @Test
    @DisplayName("조회 개수를 제한한다")
    void appliesLimit() {
        for (int i = 0; i < 5; i++) {
            repository.save(alert("005930", AnomalyType.PRICE_SURGE, NOW.plusSeconds(i)));
        }

        assertEquals(3, repository.findRecent(3).size());
    }

    @Test
    @DisplayName("종목별로 조회한다")
    void findsBySymbol() {
        repository.save(alert("005930", AnomalyType.PRICE_SURGE, NOW));
        repository.save(alert("000660", AnomalyType.PRICE_DROP, NOW));

        assertEquals(1, repository.findBySymbol("000660", 10).size());
        assertTrue(repository.findBySymbol("999999", 10).isEmpty());
    }

    @Test
    @DisplayName("보존 기간이 지난 알림을 지운다")
    void deletesOldAlerts() {
        repository.save(alert("005930", AnomalyType.PRICE_SURGE, NOW.minus(Duration.ofDays(100))));
        repository.save(alert("005930", AnomalyType.PRICE_SURGE, NOW));

        int deleted = repository.deleteDetectedBefore(NOW.minus(Duration.ofDays(30)));

        assertEquals(1, deleted);
        assertEquals(1, repository.findRecent(10).size());
    }
}
