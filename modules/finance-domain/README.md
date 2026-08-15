# finance-domain

Pure Java financial value objects and lifecycle models.

- Public API: `Account`, `Position`, `Order`, `OrderCommand`, `Quote`, `Candle`
- Dependencies: none
- Owns validation and state transitions; it does not call brokers, databases, or UI code.
- `Account` and `OrderCommand` are the single canonical models. UI-only duplicate models are not allowed.

Test: `./gradlew :modules:finance-domain:test`
