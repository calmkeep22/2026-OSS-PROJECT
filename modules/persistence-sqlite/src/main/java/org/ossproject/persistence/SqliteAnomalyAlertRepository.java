package org.ossproject.persistence;

import org.ossproject.anomaly.AnomalyAlert;
import org.ossproject.anomaly.AnomalyAlertRepository;
import org.ossproject.anomaly.AnomalySeverity;
import org.ossproject.anomaly.AnomalyType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** SQLite 기반 이상 감지 이력 저장소. */
public final class SqliteAnomalyAlertRepository implements AnomalyAlertRepository {

    private static final String INSERT = """
            INSERT INTO anomaly_alerts (symbol, stock_name, anomaly_type, severity,
                                        observed_ratio, explanation, detected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BASE = """
            SELECT symbol, stock_name, anomaly_type, severity, observed_ratio, explanation, detected_at
            FROM anomaly_alerts
            """;

    private final SqliteDatabase database;

    public SqliteAnomalyAlertRepository(SqliteDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("데이터베이스는 필수입니다.");
        }
        this.database = database;
    }

    @Override
    public void save(AnomalyAlert alert) {
        if (alert == null) {
            throw new IllegalArgumentException("알림은 필수입니다.");
        }
        Connection connection = database.connection();
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                statement.setString(1, alert.symbol());
                statement.setString(2, alert.stockName());
                statement.setString(3, alert.type().name());
                statement.setString(4, alert.severity().name());
                statement.setString(5, alert.observedRatio().toPlainString());
                statement.setString(6, alert.explanation());
                statement.setLong(7, alert.detectedAt().toEpochMilli());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("이상 감지 알림을 저장하지 못했습니다.", e);
            }
        }
    }

    @Override
    public List<AnomalyAlert> findRecent(int limit) {
        return query(SELECT_BASE + " ORDER BY detected_at DESC, id DESC LIMIT ?",
                statement -> statement.setInt(1, normalizeLimit(limit)));
    }

    @Override
    public List<AnomalyAlert> findBySymbol(String symbol, int limit) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        return query(SELECT_BASE + " WHERE symbol = ? ORDER BY detected_at DESC, id DESC LIMIT ?",
                statement -> {
                    statement.setString(1, symbol);
                    statement.setInt(2, normalizeLimit(limit));
                });
    }

    @Override
    public int deleteDetectedBefore(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("기준 시각은 필수입니다.");
        }
        Connection connection = database.connection();
        synchronized (connection) {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM anomaly_alerts WHERE detected_at < ?")) {
                statement.setLong(1, cutoff.toEpochMilli());
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("오래된 알림을 정리하지 못했습니다.", e);
            }
        }
    }

    @FunctionalInterface
    private interface ParameterBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<AnomalyAlert> query(String sql, ParameterBinder binder) {
        Connection connection = database.connection();
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                List<AnomalyAlert> alerts = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        alerts.add(new AnomalyAlert(
                                resultSet.getString("symbol"),
                                resultSet.getString("stock_name"),
                                AnomalyType.valueOf(resultSet.getString("anomaly_type")),
                                AnomalySeverity.valueOf(resultSet.getString("severity")),
                                new BigDecimal(resultSet.getString("observed_ratio")),
                                resultSet.getString("explanation"),
                                Instant.ofEpochMilli(resultSet.getLong("detected_at"))));
                    }
                }
                return List.copyOf(alerts);
            } catch (SQLException e) {
                throw new PersistenceException("이상 감지 이력을 읽지 못했습니다.", e);
            }
        }
    }

    private static int normalizeLimit(int limit) {
        return limit <= 0 ? 50 : limit;
    }
}
