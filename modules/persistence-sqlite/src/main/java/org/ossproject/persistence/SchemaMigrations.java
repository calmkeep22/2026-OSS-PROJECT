package org.ossproject.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 스키마 버전 관리.
 *
 * <p>SQLite 의 {@code user_version} 프라그마에 적용된 버전을 기록한다. 프로그램이 업데이트되어
 * 스키마가 바뀌어도 기존 사용자의 주문 이력이 날아가지 않도록, 마이그레이션은 항상 추가만 한다.
 *
 * <p>금액은 {@code REAL} 이 아니라 {@code TEXT} 로 저장한다. 부동소수점은 금액 계산에서
 * 오차를 만들고, 한 번 저장된 값은 되돌릴 수 없기 때문이다.
 */
final class SchemaMigrations {

    /** 버전 1부터 순서대로 적용한다. 기존 항목은 절대 수정하지 않고 뒤에만 추가한다. */
    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS orders (
                        order_id        TEXT    PRIMARY KEY,
                        symbol          TEXT    NOT NULL,
                        name            TEXT    NOT NULL,
                        side            TEXT    NOT NULL,
                        order_type      TEXT    NOT NULL,
                        quantity        INTEGER NOT NULL,
                        limit_price     TEXT,
                        status          TEXT    NOT NULL,
                        filled_quantity INTEGER NOT NULL,
                        filled_amount   TEXT    NOT NULL,
                        reject_reason   TEXT,
                        created_at      INTEGER NOT NULL,
                        updated_at      INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_orders_created_at ON orders(created_at DESC)",
                    "CREATE INDEX IF NOT EXISTS idx_orders_symbol ON orders(symbol)",
                    "CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)",
                    """
                    CREATE TABLE IF NOT EXISTS executions (
                        execution_id TEXT    PRIMARY KEY,
                        order_id     TEXT    NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
                        symbol       TEXT    NOT NULL,
                        side         TEXT    NOT NULL,
                        quantity     INTEGER NOT NULL,
                        price        TEXT    NOT NULL,
                        executed_at  INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_executions_order ON executions(order_id)",
                    """
                    CREATE TABLE IF NOT EXISTS anomaly_alerts (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        symbol        TEXT    NOT NULL,
                        stock_name    TEXT    NOT NULL,
                        anomaly_type  TEXT    NOT NULL,
                        severity      TEXT    NOT NULL,
                        observed_ratio TEXT   NOT NULL,
                        explanation   TEXT    NOT NULL,
                        detected_at   INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_alerts_detected_at ON anomaly_alerts(detected_at DESC)",
                    "CREATE INDEX IF NOT EXISTS idx_alerts_symbol ON anomaly_alerts(symbol)"
            )
    );

    private SchemaMigrations() {
    }

    static void apply(Connection connection) {
        try {
            int currentVersion = readVersion(connection);
            for (int version = currentVersion; version < MIGRATIONS.size(); version++) {
                applyMigration(connection, MIGRATIONS.get(version), version + 1);
            }
        } catch (SQLException e) {
            throw new PersistenceException("데이터베이스 스키마를 준비하지 못했습니다.", e);
        }
    }

    private static void applyMigration(Connection connection, List<String> statements, int newVersion)
            throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            // user_version 은 바인딩 파라미터를 쓸 수 없어 정수를 직접 넣는다.
            statement.execute("PRAGMA user_version=" + newVersion);
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA user_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }
}
