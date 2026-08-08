package org.ossproject.persistence;

import org.ossproject.application.port.OrderRepository;
import org.ossproject.finance.model.Execution;
import org.ossproject.finance.model.Order;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.OrderStatus;
import org.ossproject.finance.model.OrderType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 기반 주문 저장소.
 *
 * <p>주문과 체결을 하나의 트랜잭션으로 저장한다. 주문만 저장되고 체결이 빠지면 잔고 계산이
 * 어긋나므로, 부분 저장은 허용하지 않는다.
 *
 * <p>체결은 매번 지우고 다시 넣는다. 주문당 체결 건수가 많아야 수십 건이라 성능 문제가 없고,
 * 저장 로직이 단순해져 상태가 어긋날 여지가 줄어든다.
 */
public final class SqliteOrderRepository implements OrderRepository {

    private static final String UPSERT_ORDER = """
            INSERT INTO orders (order_id, symbol, name, side, order_type, quantity, limit_price,
                                status, filled_quantity, filled_amount, reject_reason,
                                created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(order_id) DO UPDATE SET
                status          = excluded.status,
                filled_quantity = excluded.filled_quantity,
                filled_amount   = excluded.filled_amount,
                reject_reason   = excluded.reject_reason,
                updated_at      = excluded.updated_at
            """;

    private static final String INSERT_EXECUTION = """
            INSERT INTO executions (execution_id, order_id, symbol, side, quantity, price, executed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_ORDER_BASE = """
            SELECT order_id, symbol, name, side, order_type, quantity, limit_price, status,
                   filled_quantity, filled_amount, reject_reason, created_at, updated_at
            FROM orders
            """;

    private final SqliteDatabase database;

    public SqliteOrderRepository(SqliteDatabase database) {
        if (database == null) {
            throw new IllegalArgumentException("데이터베이스는 필수입니다.");
        }
        this.database = database;
    }

    @Override
    public void save(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("주문은 필수입니다.");
        }
        Connection connection = database.connection();
        synchronized (connection) {
            boolean autoCommit = true;
            try {
                autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);

                writeOrder(connection, order);
                replaceExecutions(connection, order);

                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw new PersistenceException("주문 " + order.orderId() + " 을(를) 저장하지 못했습니다.", e);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        }
    }

    @Override
    public Optional<Order> findById(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        Connection connection = database.connection();
        synchronized (connection) {
            try (PreparedStatement statement =
                         connection.prepareStatement(SELECT_ORDER_BASE + " WHERE order_id = ?")) {
                statement.setString(1, orderId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readOrder(connection, resultSet));
                }
            } catch (SQLException e) {
                throw new PersistenceException("주문 " + orderId + " 을(를) 읽지 못했습니다.", e);
            }
        }
    }

    @Override
    public List<Order> findAll() {
        return query(SELECT_ORDER_BASE + " ORDER BY created_at DESC, rowid DESC", statement -> {
        }, "전체 주문");
    }

    @Override
    public List<Order> findOpen() {
        String sql = SELECT_ORDER_BASE
                + " WHERE status IN ('NEW', 'ACCEPTED', 'PARTIALLY_FILLED')"
                + " ORDER BY created_at DESC, rowid DESC";
        return query(sql, statement -> {
        }, "미체결 주문");
    }

    @Override
    public List<Order> findBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return List.of();
        }
        String sql = SELECT_ORDER_BASE + " WHERE symbol = ? ORDER BY created_at DESC, rowid DESC";
        return query(sql, statement -> statement.setString(1, symbol), "종목별 주문");
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("기준 시각은 필수입니다.");
        }
        Connection connection = database.connection();
        synchronized (connection) {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM orders WHERE created_at < ?")) {
                statement.setLong(1, cutoff.toEpochMilli());
                return statement.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("오래된 주문을 정리하지 못했습니다.", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 쓰기
    // ------------------------------------------------------------------

    private void writeOrder(Connection connection, Order order) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_ORDER)) {
            statement.setString(1, order.orderId());
            statement.setString(2, order.symbol());
            statement.setString(3, order.name());
            statement.setString(4, order.side().name());
            statement.setString(5, order.type().name());
            statement.setLong(6, order.quantity());
            setNullableDecimal(statement, 7, order.limitPrice());
            statement.setString(8, order.status().name());
            statement.setLong(9, order.filledQuantity());
            statement.setString(10, order.filledAmount().toPlainString());
            statement.setString(11, order.rejectReason());
            statement.setLong(12, order.createdAt().toEpochMilli());
            statement.setLong(13, order.updatedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private void replaceExecutions(Connection connection, Order order) throws SQLException {
        try (PreparedStatement delete =
                     connection.prepareStatement("DELETE FROM executions WHERE order_id = ?")) {
            delete.setString(1, order.orderId());
            delete.executeUpdate();
        }
        if (order.executions().isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_EXECUTION)) {
            for (Execution execution : order.executions()) {
                insert.setString(1, execution.executionId());
                insert.setString(2, execution.orderId());
                insert.setString(3, execution.symbol());
                insert.setString(4, execution.side().name());
                insert.setLong(5, execution.quantity());
                insert.setString(6, execution.price().toPlainString());
                insert.setLong(7, execution.executedAt().toEpochMilli());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    /** {@link PreparedStatement} 에 파라미터를 채우는 콜백. */
    @FunctionalInterface
    private interface ParameterBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<Order> query(String sql, ParameterBinder binder, String label) {
        Connection connection = database.connection();
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                List<Order> orders = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        orders.add(readOrder(connection, resultSet));
                    }
                }
                return List.copyOf(orders);
            } catch (SQLException e) {
                throw new PersistenceException(label + " 을(를) 읽지 못했습니다.", e);
            }
        }
    }

    private Order readOrder(Connection connection, ResultSet resultSet) throws SQLException {
        String orderId = resultSet.getString("order_id");
        return new Order(
                orderId,
                resultSet.getString("symbol"),
                resultSet.getString("name"),
                OrderSide.valueOf(resultSet.getString("side")),
                OrderType.valueOf(resultSet.getString("order_type")),
                resultSet.getLong("quantity"),
                readNullableDecimal(resultSet, "limit_price"),
                OrderStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("filled_quantity"),
                new BigDecimal(resultSet.getString("filled_amount")),
                readExecutions(connection, orderId),
                resultSet.getString("reject_reason"),
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at")));
    }

    private List<Execution> readExecutions(Connection connection, String orderId) throws SQLException {
        String sql = """
                SELECT execution_id, order_id, symbol, side, quantity, price, executed_at
                FROM executions WHERE order_id = ? ORDER BY executed_at ASC, rowid ASC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, orderId);
            List<Execution> executions = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    executions.add(new Execution(
                            resultSet.getString("execution_id"),
                            resultSet.getString("order_id"),
                            resultSet.getString("symbol"),
                            OrderSide.valueOf(resultSet.getString("side")),
                            resultSet.getLong("quantity"),
                            new BigDecimal(resultSet.getString("price")),
                            Instant.ofEpochMilli(resultSet.getLong("executed_at"))));
                }
            }
            return List.copyOf(executions);
        }
    }

    private static void setNullableDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value.toPlainString());
        }
    }

    private static BigDecimal readNullableDecimal(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? null : new BigDecimal(value);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 롤백 실패는 원래 오류를 덮지 않도록 삼킨다.
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // 위와 같다.
        }
    }
}
