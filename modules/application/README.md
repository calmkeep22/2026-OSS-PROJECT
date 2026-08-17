# application

Application use cases, ports, and cross-adapter policies.

- Public API: `MarketApplicationPort`, `MarketApplicationListener`, `EventSubscription`,
  `TradingUseCase`, query/stream/order/account ports, `OrderGuard`
- Depends on: `finance-domain`
- Does not contain JavaFX, broker SDKs, SQL, or operating-system implementations.
- Reusable adapter contracts live in `src/testFixtures`.

## 시장 Application 계층

`MarketApplicationService`는 저수준 `StockQueryPort`, `CandleQueryPort`,
`MarketDataStreamPort`를 JavaFX가 사용하는 하나의 비동기 경계로 묶습니다.

```java
MarketApplicationPort market = new MarketApplicationService(
        stockQueryPort,
        candleQueryPort,
        marketDataStreamPort,
        ioExecutor,
        eventExecutor);

market.search("005930", 20).thenAccept(results -> { /* UI 스레드로 전달 */ });

EventSubscription subscription = market.monitor(securityId, listener);
// 화면이 닫힐 때
subscription.close();
// 앱이 종료될 때
market.close();
```

- REST·DB 가능성이 있는 조회는 `CompletionStage`로 반환합니다.
- 같은 종목을 여러 화면이 구독하면 마지막 화면이 닫힐 때 실제 구독을 해제합니다.
- `MarketApplicationPort.close()`는 남은 실시간 연결을 멱등하게 종료합니다.
- 리스너 콜백은 `eventExecutor`로 넘겨 WebSocket 수신 루프를 막지 않습니다.
- 현재 저수준 스트림은 종목코드 문자열만 제공하므로 동일 코드의 KRX/NXT 동시 구독은
  잘못된 시세를 전달하는 대신 명시적으로 거부합니다. 저수준 `Quote`가 `SecurityId`를
  제공하면 이 제한을 제거합니다.

Test: `./gradlew :modules:application:test`
