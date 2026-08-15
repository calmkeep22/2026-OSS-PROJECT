# kiwoom-adapter

Kiwoom REST and WebSocket adapter.

- Supported entry points: `KiwoomRestClient`, `KiwoomMarketDataStream`, `KiwoomProperties`
- Depends on: `broker-api`, `application`, `finance-domain`
- HTTP/WebSocket sessions, protocol parsing, and reconnect scheduling are internal details.
- Endpoint and field mappings must be checked against the official Kiwoom specification before live use.

Test: `./gradlew :modules:kiwoom-adapter:test`
