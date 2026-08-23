# persistence-sqlite

SQLite adapters for durable financial and anomaly data.

- Implements application persistence boundaries using JDBC/SQLite
- Depends on: `application`, `finance-domain`, `anomaly-detection`
- SQL schema and migrations belong here; JavaFX and broker calls do not.
- The desktop composition root stores successful order/cancel responses and anomaly alerts here.
- Order history can be read from the local cache during a broker outage, but open-order state never
  falls back to stale SQLite data.
- The desktop database is created at `%LOCALAPPDATA%\OpenStockAccess\openstock.db` on Windows.
- Credentials and API secrets must never be stored in SQLite.

Test: `./gradlew :modules:persistence-sqlite:test`
