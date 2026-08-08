package org.ossproject.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 연결과 스키마를 관리한다.
 *
 * <p>데스크톱 프로그램이라 동시 접속자가 없으므로 커넥션 풀 대신 연결 하나를 재사용한다.
 * 대신 여러 스레드가 같은 연결을 쓰므로 저장소 구현이 연결 단위로 동기화한다.
 *
 * <p>{@code journal_mode=WAL} 로 비정상 종료 시 손상 가능성을 줄이고,
 * {@code foreign_keys=ON} 으로 주문이 지워지면 체결도 함께 지워지게 한다.
 */
public final class SqliteDatabase implements AutoCloseable {

    private final Connection connection;

    private SqliteDatabase(Connection connection) {
        this.connection = connection;
    }

    /** 파일 기반 데이터베이스를 연다. 없으면 만든다. */
    public static SqliteDatabase open(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("데이터베이스 경로는 필수입니다.");
        }
        return openUrl("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /** 메모리 데이터베이스를 연다. 테스트용이다. */
    public static SqliteDatabase openInMemory() {
        return openUrl("jdbc:sqlite:file:oss-" + System.nanoTime() + "?mode=memory&cache=shared");
    }

    private static SqliteDatabase openUrl(String url) {
        try {
            Connection connection = DriverManager.getConnection(url);
            applyPragmas(connection);
            SqliteDatabase database = new SqliteDatabase(connection);
            SchemaMigrations.apply(connection);
            return database;
        } catch (SQLException e) {
            throw new PersistenceException("데이터베이스를 열지 못했습니다.", e);
        }
    }

    private static void applyPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=3000");
        }
    }

    Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("데이터베이스를 닫지 못했습니다.", e);
        }
    }
}
