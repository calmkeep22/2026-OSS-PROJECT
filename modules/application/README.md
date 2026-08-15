# application

Application use cases, ports, and cross-adapter policies.

- Public API: `TradingUseCase`, query/stream/order/account ports, `OrderGuard`
- Depends on: `finance-domain`
- Does not contain JavaFX, broker SDKs, SQL, or operating-system implementations.
- Reusable adapter contracts live in `src/testFixtures`.

Test: `./gradlew :modules:application:test`
