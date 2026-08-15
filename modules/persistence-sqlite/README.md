# persistence-sqlite

SQLite adapters for durable financial and anomaly data.

- Implements application persistence boundaries using JDBC/SQLite
- Depends on: `application`, `finance-domain`, `anomaly-detection`
- SQL schema and migrations belong here; JavaFX and broker calls do not.
- Credentials and API secrets must never be stored in SQLite.

Test: `./gradlew :modules:persistence-sqlite:test`
