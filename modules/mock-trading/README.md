# mock-trading

Stateful in-memory trading engine for demos and deterministic tests.

- Public API: `MockTradingEngine`, `FillMode`, `DemoTradingAccounts`
- Implements: `OrderLifecyclePort`, `AccountPort`, `OrderEventSource`, `QuoteListener`
- Depends on: `application`, `finance-domain`
- Models reservations, acceptance, fills, cancellation, balances, and positions.

Test: `./gradlew :modules:mock-trading:test`
