# fake-adapters

Controllable adapters for UI development and contract tests.

- Public API: `FakeStockQueryAdapter`, `FakeCandleQueryAdapter`, `FakeMarketDataStreamAdapter`
- Depends on: `application`, `finance-domain`
- Data is deterministic when a fixed `Clock` is supplied.
- The market stream follows the same reusable contract as the Kiwoom stream.

Test: `./gradlew :modules:fake-adapters:test`
