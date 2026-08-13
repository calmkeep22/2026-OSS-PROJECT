# A/B 인터페이스별 구현 명세

> 상태: 합의 전 상세 초안
> 기준 계약: [A-B-INTEGRATION-CONTRACT.md](A-B-INTEGRATION-CONTRACT.md)
> 작성일: 2026-08-10

이 문서는 개발자 A와 B가 이슈를 인터페이스 단위로 나누어 구현할 수 있도록 공개 메서드,
입출력 모델, 구현 규칙, 완료 조건을 정리한다. 여기서 `MUST`로 표시한 항목만 1차 구현
범위다.

## 1. 공통 모델

### 1.1 식별자

```java
public enum TradingEnvironment {
    LOCAL_SIMULATION,
    KIWOOM_MOCK,
    KIWOOM_REAL
}

public enum Exchange {
    KRX,
    NXT,
    SOR
}

public record SecurityId(String symbol, Exchange exchange) {
}

public record AccountRef(
        String accountId,
        String maskedAccountNo,
        String alias,
        TradingEnvironment environment
) {
}
```

- `accountId`는 로컬 UUID다. 전체 계좌번호를 공개 모델에 넣지 않는다.
- 종목을 받는 신규 인터페이스는 `String symbol` 대신 `SecurityId`를 사용한다.
- 외부 주문·체결 ID는 숫자처럼 보여도 `String`으로 취급한다.

### 1.2 주문 명령과 확인 모델

```java
public record OrderCommand(
        SecurityId security,
        String securityName,
        OrderSide side,
        OrderType type,
        long quantity,
        BigDecimal limitPrice
) {
}

public record AmendOrderCommand(
        String localOrderId,
        long newQuantity,
        BigDecimal newLimitPrice
) {
}

public record CancelOrderCommand(String localOrderId) {
}
```

```java
public record ConfirmableOrderPreview(
        String previewId,
        OrderOperation operation,
        String targetLocalOrderId,
        AccountRef account,
        SecurityId security,
        String securityName,
        OrderSide side,
        OrderType type,
        long quantity,
        BigDecimal limitPrice,
        BigDecimal estimatedAmount,
        BigDecimal estimatedCashAfterOrder,
        Instant expiresAt
) {
}
```

- `previewId`는 일회용이고 기본 유효시간은 30초다.
- 실전 신규·정정·취소는 모두 미리보기와 명시적 확인을 거친다.
- 음성 명령만으로 주문을 최종 제출할 수 없다.

### 1.3 주문 결과와 이벤트 모델

`Order`는 목표 계약에서 최소 다음 값을 가진다.

```text
localOrderId, brokerOrderId, clientRequestId, account, security, securityName,
side, type, quantity, limitPrice, status, filledQuantity, filledAmount,
executions, safeRejectReason, createdAt, updatedAt
```

```java
public enum BrokerOrderOutcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN
}

public record BrokerOrderResult(
        String clientRequestId,
        String brokerOrderId,
        BrokerOrderOutcome outcome,
        String reasonCode,
        String safeReason,
        Instant occurredAt
) {
}
```

```text
OrderEvent:
eventId, localOrderId, brokerOrderId, eventType, status,
filledQuantity, remainingQuantity, fillPrice, reasonCode, safeReason,
occurredAt, receivedAt

Execution:
executionId, brokerExecutionId, localOrderId, brokerOrderId, account,
security, side, quantity, price, commission, tax, executedAt, receivedAt
```

`BrokerOrderEvent`, `BrokerExecutionEvent`, `BrokerBalanceEvent`는 A가 키움 원문을 변환한
어댑터 전용 정규화 record다. B의 JavaFX가 이 타입을 직접 사용하지 않는다.

### 1.4 시장·계좌 모델

```text
SecuritySummary: SecurityId security, String name

Quote:
SecurityId security, price, previousClose, bidPrice, askPrice,
bidSize, askSize, cumulativeVolume, occurredAt, receivedAt

AccountSnapshot:
AccountRef account, Balance balance, List<Position> positions, Instant asOf

SyncCheckpoint:
AccountRef account, SyncResource resource, lastSequence,
lastEventAt, lastSuccessAt
```

가격·금액은 `BigDecimal`, 수량·거래량은 `long`, 도메인 시각은 `Instant`를 사용한다.

## 2. 데이터베이스 모델

JPA Entity나 SQLite 행 타입을 `finance-domain`에 넣지 않는다. `persistence-sqlite`가 도메인
모델과 행을 변환하며 Application 계층에는 Repository 인터페이스만 노출한다.

### 2.1 1차 필수 테이블

| 테이블 | 대응 모델 | 핵심 키·필드 |
|---|---|---|
| `accounts` | `AccountRef` | `account_id` PK, `masked_account_no`, `environment`, `credential_alias` |
| `orders` | `Order` | `local_order_id` PK, `client_request_id`, `broker_order_id`, `account_id`, 상태·수량·가격·시각 |
| `order_events` | `OrderEvent` | `event_id` PK, `local_order_id`, 상태·체결량·사유·시각 |
| `executions` | `Execution` | `execution_id` PK, `broker_execution_id`, 주문 ID, 수량·가격·수수료·세금 |
| `balance_snapshots` | `AccountSnapshot.balance` | `snapshot_id`, `account_id`, 예수금·주문가능금액·평가금액·시각 |
| `position_snapshots` | `AccountSnapshot.positions` | `snapshot_id`, `account_id`, 종목·수량·평균가·현재가·손익 |
| `sync_checkpoints` | `SyncCheckpoint` | `(account_id, resource_type)` PK, 마지막 이벤트·성공 시각 |
| `accessibility_settings` | `AccessibilityPreferences` | `profile_id` PK, 음성·글자·대비·스크린리더 설정 |
| `sonification_settings` | `SonificationPreferences` | `profile_id` PK, 음역·볼륨·정규화·음성 기준점 |

관계는 다음과 같다.

```text
accounts 1 ── N orders 1 ── N order_events
                    └────── 1 ── N executions
accounts 1 ── N balance_snapshots
accounts 1 ── N position_snapshots
accounts 1 ── N sync_checkpoints
```

### 2.2 DB 공통 규칙

- 주문과 그 주문에 딸린 체결·이벤트는 한 트랜잭션으로 저장한다.
- `UNIQUE(account_id, broker_order_id)`를 적용하되 `broker_order_id IS NULL`은 제외한다.
- `UNIQUE(account_id, broker_execution_id)`로 중복 체결을 막는다.
- 금액은 SQLite `TEXT`, 수량과 epoch 시각은 `INTEGER`, ID와 enum은 `TEXT`로 저장한다.
- 외래키와 WAL을 활성화한다.
- App Key, Secret, 토큰, 전체 계좌번호·비밀번호는 SQLite에 저장하지 않는다.
- 모의와 실전 DB는 파일을 분리한다.
- 기존 마이그레이션을 수정하지 않고 다음 버전 SQL을 추가한다.

### 2.3 2단계 또는 선택 테이블

- `candles`: 오프라인 차트·그래프 소리 재생이 필요할 때 추가한다.
- `watchlists`, `watchlist_items`: 관심종목 정책과 UI 확정 후 추가한다.
- `notification_history`: 사용자가 알림 이력 기능을 요구할 때만 추가한다.

## 3. 모든 인터페이스의 공통 규칙

1. 공개 DTO는 불변 record로 만들고 생성 시 유효성을 검사한다.
2. 키움 JSON 필드, TR 코드, `Map<String, Object>`를 공개 Port 밖으로 노출하지 않는다.
3. A의 네트워크 Port는 동기식일 수 있지만 B의 UI가 직접 호출하지 않는다.
4. 네트워크와 SQLite를 사용하는 B의 Application Port는 `CompletionStage`를 반환한다.
5. JavaFX Node는 JavaFX Application Thread에서만 변경한다.
6. WebSocket 수신 스레드에서 DB·TTS·JavaFX 작업을 직접 실행하지 않는다.
7. 시세는 중간 값을 합칠 수 있지만 주문·체결 이벤트는 버리거나 합치지 않는다.
8. 주문 변경 요청은 일반 조회처럼 자동 재시도하지 않는다.
9. 오류 분기는 `ApplicationErrorCode`, 사용자 표시·TTS는 `safeMessage`를 사용한다.
10. 모든 `close()`는 여러 번 호출해도 안전해야 한다.

공동 구독 수명 인터페이스는 다음과 같다.

```java
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}
```

리스너 등록은 `EventSubscription`을 반환하고 화면 종료·종목 변경·계좌 전환 시 B가 닫는다.

## 4. 개발자 A 구현 인터페이스

### 1. `SecurityQueryPort`

목적: 키움 종목 검색·상세 조회를 Application 계층과 분리한다.

```java
public interface SecurityQueryPort {
    List<SecuritySummary> search(String query, int limit);
    StockDetail getDetail(SecurityId security);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/kiwoom-adapter`
- `search`: 종목코드 또는 종목명으로 검색하고 최대 `limit`개를 반환한다.
- `getDetail`: 현재가, 전일 종가, 등락, 거래량 등 상세 스냅샷을 반환한다.
- 빈 검색어와 `limit <= 0`은 `VALIDATION_ERROR`로 거부한다.
- 키움 페이지네이션과 원본 종목 코드는 구현 내부에서 처리한다.
- 테스트: 빈 검색어, 결과 제한, KRX/NXT 동일 코드 구분, 미존재 종목 오류.

### 2. `CandleQueryPort`

목적: 화면 차트와 Sonification이 동일한 봉 데이터를 사용하게 한다.

```java
public interface CandleQueryPort {
    List<Candle> getCandles(SecurityId security, CandleInterval interval, int count);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/kiwoom-adapter`
- 반환 순서는 오래된 봉부터 최신 봉 순이다.
- `count <= 0`은 거부한다.
- 키움 연속조회는 내부에서 끝까지 처리하되 호출 제한을 지킨다.
- 빈 구간을 임의의 0원 봉으로 채우지 않는다.
- 테스트: 정렬, 가격 정밀도, 거래량, 페이지 연결, 빈 결과.

### 3. `AccountQueryPort`

목적: 계좌 목록과 잔고·보유종목의 원격 원본을 조회한다.

```java
public interface AccountQueryPort {
    List<AccountRef> getAccounts(TradingEnvironment environment);
    AccountSnapshot getSnapshot(AccountRef account);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/kiwoom-adapter`
- 전체 계좌번호는 반환하지 않고 `AccountRef`만 반환한다.
- `getSnapshot`은 잔고와 보유종목을 같은 기준 시각으로 조립한다.
- 모의 계좌와 실전 계좌를 섞지 않는다.
- 테스트: 계좌 마스킹, 환경 분리, 빈 보유종목, 금액 정밀도, 인증 만료.

### 4. `BrokerOrderPort`

목적: 키움 주문 전송 결과만 정규화하고 도메인 상태 관리는 Application Service에 맡긴다.

```java
public interface BrokerOrderPort {
    BrokerOrderResult submit(AccountRef account, String clientRequestId,
                             OrderCommand command);
    BrokerOrderResult amend(AccountRef account, String clientRequestId,
                            String brokerOrderId, AmendOrderCommand command);
    BrokerOrderResult cancel(AccountRef account, String clientRequestId,
                             String brokerOrderId);
    List<BrokerOrderSnapshot> getOrders(AccountRef account, Instant fromInclusive);
    List<BrokerExecution> getExecutions(AccountRef account, Instant fromInclusive);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/kiwoom-adapter`
- `submit`, `amend`, `cancel`은 자동 재시도하지 않는다.
- 응답을 확정할 수 없으면 `BrokerOrderOutcome.UNKNOWN`을 반환한다.
- A가 `Order`를 생성하거나 상태를 전이시키지 않는다.
- `getOrders`, `getExecutions`는 시작·재연결·UNKNOWN 조정에 사용한다.
- 테스트: 승인·거부·타임아웃, 중복 제출 방지, 원주문번호, 조회 페이지네이션.

### 5. `MarketDataStreamPort`

목적: 유실 가능하고 최신값이 중요한 실시간 시세를 전달한다.

```java
public interface MarketDataStreamPort extends AutoCloseable {
    void connect();
    void subscribe(Collection<SecurityId> securities);
    void unsubscribe(Collection<SecurityId> securities);
    Set<SecurityId> subscriptions();
    EventSubscription onQuote(QuoteListener listener);
    EventSubscription onConnectionChange(ConnectionListener listener);
    ConnectionState connectionState();
    @Override void close();
}

@FunctionalInterface
public interface QuoteListener {
    void onQuote(Quote quote);
}

@FunctionalInterface
public interface ConnectionListener {
    void onConnectionStateChanged(ConnectionState state, String safeDetail);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/kiwoom-adapter`, 테스트용 `modules/fake-adapters`
- `CONNECTED`는 소켓 연결뿐 아니라 LOGIN 성공까지 끝난 상태다.
- 재연결 뒤 기존 구독을 자동 복구한다.
- 느린 소비자를 위해 같은 종목의 중간 Quote를 최신값으로 합칠 수 있다.
- 테스트: LOGIN/REG/REMOVE/PING, 재연결, 구독 복구, 리스너 해제, STALE 전이.

### 6. `BrokerTradingStreamPort`

목적: 유실하면 안 되는 주문·체결·잔고 이벤트를 Application Service에 전달한다.

```java
public interface BrokerTradingStreamPort extends AutoCloseable {
    void connect(AccountRef account);
    EventSubscription listen(BrokerTradingEventListener listener);
    ConnectionState connectionState();
    @Override void close();
}

public interface BrokerTradingEventListener {
    void onOrderEvent(BrokerOrderEvent event);
    void onExecution(BrokerExecutionEvent event);
    void onBalanceEvent(BrokerBalanceEvent event);
    void onConnectionChanged(ConnectionState state, String safeDetail);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/kiwoom-adapter`, 테스트용 `modules/fake-adapters`
- B의 JavaFX·TTS가 이 Port를 직접 구독하면 안 된다.
- 이벤트를 합치거나 누락하지 않는다.
- 한 Listener의 예외가 수신 루프나 다른 Listener를 종료시키면 안 된다.
- 재연결 후 REST 조회가 끝나기 전에는 정상 동기화 상태로 알리지 않는다.
- 테스트: 주문·체결·잔고 타입 변환, 중복 이벤트, 순서, 재연결 누락 복구.

### 7. `OrderRepository`

목적: 주문 aggregate와 체결을 저장하고 broker 이벤트를 로컬 주문에 연결한다.

```java
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(String localOrderId);
    Optional<Order> findByClientRequestId(AccountRef account, String clientRequestId);
    Optional<Order> findByBrokerOrderId(AccountRef account, String brokerOrderId);
    List<Order> findOpen(AccountRef account);
    List<Order> findRecent(AccountRef account, int limit);
    int deleteCreatedBefore(Instant cutoff);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/persistence-sqlite`
- `save`는 주문과 포함된 체결을 한 트랜잭션으로 upsert한다.
- client 요청 ID와 broker 주문 ID 조회는 멱등성과 이벤트 연결에 필수다.
- 전체 계좌를 섞은 `findAll()` 대신 계좌 범위를 명시한다.
- 테스트: 저장 왕복, 중복 체결, 계좌 격리, 미체결 조회, 금액 정밀도.

### 8. `OrderEventRepository`

목적: 상태 변경 이벤트를 감사하고 같은 broker 이벤트의 중복 반영을 막는다.

```java
public interface OrderEventRepository {
    boolean append(OrderEvent event);
    List<OrderEvent> findByOrderId(String localOrderId);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/persistence-sqlite`
- 같은 `eventId`면 `append`는 저장하지 않고 `false`를 반환한다.
- 반환 이벤트는 `occurredAt`, `receivedAt` 순으로 안정적으로 정렬한다.
- 테스트: 중복 append, 순서, 거부 사유, 재시작 후 중복 제거.

### 9. `AccountSnapshotRepository`

목적: 마지막으로 확인된 잔고와 보유종목을 저장한다.

```java
public interface AccountSnapshotRepository {
    void save(AccountSnapshot snapshot);
    Optional<AccountSnapshot> findLatest(AccountRef account);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/persistence-sqlite`
- 잔고와 보유종목 목록은 한 트랜잭션으로 저장한다.
- `findLatest`는 데이터 신선도를 숨기지 않고 원래 `asOf`를 반환한다.
- 테스트: 빈 포트폴리오, 다중 계좌 격리, 최신 스냅샷, 트랜잭션 롤백.

### 10. `SyncCheckpointRepository`

목적: 재연결과 앱 재시작 후 어디부터 REST 조정을 해야 하는지 기록한다.

```java
public interface SyncCheckpointRepository {
    Optional<SyncCheckpoint> find(AccountRef account, SyncResource resource);
    void save(SyncCheckpoint checkpoint);
}
```

- 위치: `modules/application/.../port`
- 구현: `modules/persistence-sqlite`
- `SyncResource`는 `ORDERS`, `EXECUTIONS`, `BALANCE`, `POSITIONS`다.
- 성공하지 않은 동기화는 `lastSuccessAt`을 앞으로 이동시키지 않는다.
- 테스트: 자원별 체크포인트, 실패 보존, 계좌·환경 격리.

### A의 2단계 인터페이스: `CandleRepository`

```java
public interface CandleRepository {
    void upsert(SecurityId security, Candle candle);
    List<Candle> findRecent(SecurityId security, CandleInterval interval, int count);
}
```

실시간 연결과 주문 복구에는 필수가 아니므로 1차 이슈에 넣지 않는다.

## 5. 개발자 B 구현 인터페이스

### 1. `MarketApplicationPort`

목적: JavaFX가 키움과 SQLite 구현을 모르고 시장 정보를 사용하게 한다.

```java
public interface MarketApplicationPort {
    CompletionStage<List<SecuritySummary>> search(String query, int limit);
    CompletionStage<StockDetail> loadDetail(SecurityId security);
    CompletionStage<List<Candle>> loadCandles(
            SecurityId security, CandleInterval interval, int count);
    EventSubscription monitor(SecurityId security, MarketApplicationListener listener);
}
```

- 위치: 계약은 `modules/application/.../port`, 구현은 `modules/application/.../usecase`
- 조회는 A의 Outbound Port를 작업 스레드에서 호출한다.
- `monitor`는 즉시 반환하고 Listener로 이후 시세를 전달한다.
- 같은 종목을 여러 화면이 구독할 때 실제 broker 구독은 참조 계수로 공유할 수 있다.
- 테스트: 비동기 완료·실패, 구독 해제, 느린 UI 시 최신값 병합.

### 2. `MarketApplicationListener`

목적: 정제된 시세와 연결 상태를 화면·Sonification에 전달한다.

```java
public interface MarketApplicationListener {
    void onQuote(Quote quote);
    void onConnectionChanged(ConnectionState state, String safeDetail);
}
```

- JavaFX 구현은 콜백에서 `Platform.runLater`를 사용한다.
- 화면 갱신은 기본 최대 초당 4회로 제한한다.
- Sonification은 최신 Quote를 자체 샘플링한다.
- 시세 한 틱마다 TTS를 생성하지 않는다.

### 3. `AccountApplicationPort`

목적: 저장된 마지막 상태와 키움 원격 새로고침을 명확히 구분한다.

```java
public interface AccountApplicationPort {
    CompletionStage<List<AccountRef>> accounts(TradingEnvironment environment);
    CompletionStage<Optional<AccountSnapshot>> latest(AccountRef account);
    CompletionStage<AccountSnapshot> refresh(AccountRef account);
}
```

- `accounts`: A의 계좌 조회를 비동기로 감싼다.
- `latest`: SQLite의 마지막 스냅샷만 읽으며 네트워크를 호출하지 않는다.
- `refresh`: A에서 새 스냅샷을 받은 뒤 DB에 저장하고 반환한다.
- 실전 주문 전 `asOf`와 마지막 동기화 성공 시각을 검사한다.
- 테스트: 캐시와 새로고침 구분, 오래된 데이터, 환경 전환, 오류 메시지.

### 4. `TradingApplicationPort`

목적: 주문 확인, 멱등성, 상태 전이, 저장을 통과하는 유일한 주문 진입점이다.

```java
public interface TradingApplicationPort {
    ConfirmableOrderPreview previewNew(AccountRef account, OrderCommand command);
    ConfirmableOrderPreview previewAmend(AmendOrderCommand command);
    ConfirmableOrderPreview previewCancel(CancelOrderCommand command);
    CompletionStage<Order> submitConfirmed(String previewId);
    CompletionStage<List<Order>> openOrders(AccountRef account);
    CompletionStage<List<Order>> recentOrders(AccountRef account, int limit);
    EventSubscription observe(AccountRef account, TradingApplicationListener listener);
}
```

- 위치: 계약은 `modules/application/.../port`, 구현은 `modules/application/.../usecase`
- preview 메서드는 메모리의 최신 계좌 상태만 사용하므로 동기식이다.
- 계좌 상태가 오래됐으면 미리보기를 만들지 않고 `ACCOUNT_SYNC_REQUIRED`를 반환한다.
- `submitConfirmed`만 A의 `BrokerOrderPort`를 호출한다.
- 동일 `previewId`는 한 번만 성공할 수 있다.
- broker 결과가 불명확하면 `OrderStatus.UNKNOWN`을 저장하고 반환한다.
- SQLite 조회인 `openOrders`, `recentOrders`도 UI 스레드 밖에서 실행한다.
- 테스트: 확인 없는 제출, 만료, 중복 제출, UNKNOWN, 정정·취소 확인, DB 저장.

### 5. `TradingApplicationListener`

목적: 정합성 처리와 저장이 완료된 금융 이벤트만 UI·접근성 기능에 전달한다.

```java
public interface TradingApplicationListener {
    void onOrderEvent(OrderEvent event);
    void onExecution(Execution execution);
    void onAccountChanged(AccountSnapshot snapshot);
    void onConnectionChanged(ConnectionState state, String safeDetail);
}
```

- A의 `BrokerTradingEventListener`와 다른 인터페이스다.
- 중복 제거와 DB commit 전에는 호출하지 않는다.
- 주문·체결은 버리거나 합치지 않는다.
- B는 이벤트를 화면 텍스트와 TTS 모두로 표현한다.
- 테스트: 이벤트 순서, TTS 우선순위, 중복 음성 방지, JavaFX 스레드 전환.

### 6. `AccessibilityPreferencesRepository`

목적: 접근성 설정을 재시작 후에도 유지한다.

```java
public interface AccessibilityPreferencesRepository {
    AccessibilityPreferences load();
    void save(AccessibilityPreferences preferences);
}
```

- 위치: `modules/accessibility/.../port`
- 구현: B가 작성하고 SQLite 사용 시 A가 마이그레이션을 검토한다.
- 저장값이 없으면 `null` 대신 안전한 기본값을 반환한다.
- 실전 주문 재확인은 끌 수 있는 설정으로 제공하지 않는다.
- 테스트: 기본값, 저장 왕복, 손상된 설정 복구, 글자 크기·음량 범위.

### 7. `SonificationPreferencesRepository`

목적: 사용자가 조정한 그래프 소리 설정을 유지한다.

```java
public interface SonificationPreferencesRepository {
    SonificationPreferences load();
    void save(SonificationPreferences preferences);
}
```

- 위치: `modules/sonification/.../port`
- 구현: B가 작성하고 SQLite 사용 시 A가 마이그레이션을 검토한다.
- 볼륨은 `0.0..1.0`, 음성 기준점 간격은 0보다 커야 한다.
- 테스트: 기본값, 저장 왕복, 범위 검증, 설정 버전 호환.

### 8. 기존 `SpeechPort` — 유지

```java
public interface SpeechPort extends AutoCloseable {
    void speak(String text) throws InterruptedException;
    void stop();
    default void applyOptions(SpeechOptions options) {}
    @Override default void close() { stop(); }
}
```

- 현재 구현을 유지한다.
- Windows/macOS/Linux 어댑터 차이는 Factory에서 선택한다.
- 우선순위·중복 제거는 `SpeechPort`가 아니라 기존 `SpeechQueue` 책임이다.

### 9. 기존 `SoundPort` — 유지

```java
public interface SoundPort extends AutoCloseable {
    void play(SoundCue cue);
    void stop();
    void setVolume(double volume);
    @Override default void close() { stop(); }
}
```

- 짧은 상태음 출력용이며 문장 음성은 담당하지 않는다.
- `SoundCue`는 의미가 고정된 enum으로 유지한다.

### 10. 기존 `SonificationPort` — 유지

```java
public interface SonificationPort extends AutoCloseable {
    void play(GraphAudioFrame frame);
    void stop();
    void setVolume(double volume);
    default void addOutputListener(SonificationOutputListener listener) {}
    default void removeOutputListener(SonificationOutputListener listener) {}
    @Override default void close() { stop(); }
}
```

- 그래프 값을 소리로 매핑하는 로직과 실제 오디오 출력을 분리한 현재 설계를 유지한다.
- `SpeechPort`, `SoundPort`와 합치지 않는다.

### B의 2단계 인터페이스: `WatchlistRepository`

```java
public interface WatchlistRepository {
    List<SecurityId> findAll();
    void replaceAll(List<SecurityId> securities);
}
```

관심종목 그룹·정렬·중복 정책이 확정되기 전에는 구현하지 않는다.

## 6. 실제 이벤트 처리 순서

```text
키움 WebSocket
  -> A: BrokerTradingStreamPort
  -> B: TradingApplicationService
       1. broker 이벤트 중복 검사
       2. localOrderId 연결
       3. 도메인 상태 전이
       4. Order·Execution·OrderEvent 트랜잭션 저장
       5. SyncCheckpoint 갱신
  -> TradingApplicationListener
  -> JavaFX 화면 + SpeechQueue + SoundPort
```

시세는 다음처럼 별도 흐름을 사용한다.

```text
키움 WebSocket
  -> A: MarketDataStreamPort
  -> B: MarketApplicationPort
  -> 화면 최신값 제한 갱신 / Sonification 자체 샘플링
```

## 7. 구현 순서

1. 공동 값 타입과 주문 상태를 확정한다.
2. A의 1~6번 broker Port와 Fake 구현을 만든다.
3. A의 7~10번 SQLite Repository와 마이그레이션을 만든다.
4. B의 1~5번 Application Port·Listener와 서비스를 만든다.
5. B의 설정 Repository를 만들고 기존 접근성 출력 Port에 연결한다.
6. Fake 기반 계약 테스트를 통과시킨다.
7. 키움 모의투자 어댑터를 연결한다.
8. 장애 복구와 접근성 통합 시나리오를 검증한다.
