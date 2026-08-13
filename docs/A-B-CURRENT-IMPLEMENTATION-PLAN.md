# A/B 현재 구현 상태와 인터페이스 작업 명세

> 기준일: 2026-08-10
> 목적: 현재 저장소에 실제로 있는 코드와 앞으로 구현할 계약을 개발자 A와 B로 나누어 관리한다.

이 문서는 기능 아이디어 목록이 아니다. 현재 UI를 데모 데이터가 아닌 키움 모의투자 데이터로
동작시키기 위해 필요한 공개 인터페이스, 공통 모델, 저장 모델과 구현 순서를 정한다.

상세한 주문·오류·스레드 정책은
[A-B-INTEGRATION-CONTRACT.md](A-B-INTEGRATION-CONTRACT.md)를 따르고, 각 인터페이스의
기존 초안은 [A-B-INTERFACE-SPEC.md](A-B-INTERFACE-SPEC.md)를 함께 참고한다. 두 문서와
현재 코드가 다르면 이 문서의 상태표를 기준으로 이슈를 만든다.

## 1. 상태 표기

| 표기 | 의미 |
|---|---|
| 완료 | 현재 코드에 있고 그대로 사용할 수 있음 |
| 보완 | 현재 코드에 있으나 목표 계약에 맞게 서명 또는 책임을 수정해야 함 |
| 신규 | 현재 코드에 없어서 새로 구현해야 함 |
| 임시 | 화면 확인용 구현이며 실제 연동 전에 교체해야 함 |
| 2차 | 핵심 주문·계좌 연동 뒤 구현해도 되는 기능 |

## 2. 담당 범위

### 개발자 A

- finance-domain의 금융 값 타입과 주문 상태
- 키움 REST와 WebSocket 어댑터
- 인증, 토큰, 호출 제한, 재연결
- 주문·체결·잔고 정규화
- SQLite 금융 데이터와 장애 복구
- Windows DPAPI 비밀 저장 구현
- 이상 탐지의 금융 입력 데이터

### 개발자 B

- application의 사용자 유스케이스와 비동기 Application Port
- JavaFX 화면, ViewModel, 내비게이션
- 주문 미리보기와 재확인 UX
- TTS, 상태음, 그래프 Sonification 연결
- 키보드, 스크린리더, 큰 글자, 고대비
- 관심종목·최근 검색·UI 설정 등 사용자 로컬 상태
- 데스크톱 패키징과 접근성 테스트

### 공동 승인 대상

- 공개 record와 enum의 필드
- 공개 Port의 메서드 서명
- 주문 상태 전이와 UNKNOWN 처리
- 모의/실전 환경 분리
- DB 마이그레이션
- 사용자에게 읽어 주는 주문·체결·연결 문구

## 3. 의존성 공통 규칙

허용되는 기본 흐름은 다음과 같다.

~~~text
JavaFX View
  -> B ViewModel
  -> B Application Port / Use Case
  -> A Outbound Port
  -> Kiwoom 또는 SQLite Adapter
~~~

- JavaFX와 ViewModel은 kiwoom-adapter, broker-api, SQLite 구현 클래스를 직접 import하지 않는다.
- JavaFX Node는 Application Thread에서만 변경한다.
- REST, SQLite, 토큰 발급은 JavaFX Application Thread에서 실행하지 않는다.
- WebSocket 콜백에서 TTS나 디스크 저장을 직접 실행하지 않는다.
- 시세 이벤트는 최신값 중심으로 합칠 수 있지만 주문·체결 이벤트는 버리지 않는다.
- 종목은 문자열 하나가 아니라 SecurityId로 식별한다.
- 금액과 가격은 BigDecimal, 수량은 long, 시각은 Instant를 사용한다.
- 전체 계좌번호, App Key, App Secret, Access Token은 UI 상태 파일과 로그에 저장하지 않는다.
- 모의와 실전의 자격증명, DB, 주문 ID 공간을 분리한다.
- close와 EventSubscription.close는 여러 번 호출해도 안전해야 한다.

## 4. 먼저 공동으로 추가할 모델

현재 finance-domain에는 Account, Order, Execution, Quote, Candle, StockDetail 등이 있다.
아래 타입은 목표 인터페이스가 사용하지만 아직 코드에 없다.

### 4.1 1차 필수 모델

| 모델 | 핵심 필드 | 상태 |
|---|---|---|
| TradingEnvironment | MOCK, LIVE | 신규 |
| Exchange | KRX, NXT, NASDAQ, NYSE 등 | 신규 |
| SecurityId | symbol, exchange | 신규 |
| SecuritySummary | SecurityId, name, market, currency | 신규 |
| AccountRef | brokerId, environment, maskedAccountId, internalKey | 신규 |
| AccountSnapshot | account, balance, positions, asOf, freshness | 신규 |
| ConnectionSnapshot | environment, state, tokenExpiresAt, safeMessage | 신규 |
| EventSubscription | close | 신규 |
| OrderEvent | eventId, localOrderId, status, occurredAt, receivedAt, reason | 신규 |
| SyncCheckpoint | account, resource, lastSuccessAt | 신규 |
| SyncResource | ORDERS, EXECUTIONS, BALANCE, POSITIONS | 신규 |
| AppErrorCode | VALIDATION_ERROR, AUTH_EXPIRED, NETWORK_ERROR, RATE_LIMITED, ACCOUNT_SYNC_REQUIRED 등 | 신규 |

권장 식별자 계약:

~~~java
public record SecurityId(String symbol, Exchange exchange) {}

public record AccountRef(
        String brokerId,
        TradingEnvironment environment,
        String maskedAccountId,
        String internalKey
) {}

@FunctionalInterface
public interface EventSubscription extends AutoCloseable {
    @Override void close();
}
~~~

AccountRef의 internalKey는 앱 내부 식별자이며 전체 계좌번호를 화면에 노출하는 값이 아니다.

### 4.2 주문 연동 모델

다음 모델도 BrokerOrderPort와 TradingApplicationPort를 구현하기 전에 확정한다.

- BrokerOrderResult: brokerOrderId, outcome, acceptedAt, safeMessage
- BrokerOrderOutcome: ACCEPTED, REJECTED, UNKNOWN
- BrokerOrderSnapshot: 증권사 조회 결과를 정규화한 주문 상태
- BrokerExecution: 증권사 체결 한 건
- BrokerOrderEvent, BrokerExecutionEvent, BrokerBalanceEvent
- ConfirmableOrderPreview: previewId, 만료 시각, 읽기용 확인 문장, 예상 금액
- AmendOrderCommand, CancelOrderCommand

Broker 원본 DTO와 위 공통 모델은 분리한다. 키움 필드명과 TR 코드는 kiwoom-adapter 밖으로
나오지 않는다.

### 4.3 2차 화면과 로컬 상태 모델

다음 타입은 핵심 주문·계좌 연동이 아니라 해당 화면을 실제 데이터로 전환할 때 추가한다.

- MarketId, MarketOverview
- RankingCriteria, RankingResult
- ConditionDefinition
- InvestorFlow
- WatchlistGroup, WatchlistItem
- AlertRule, JournalEntry
- AccessibilityPreferences
- SonificationPreferences

## 5. 데이터베이스 모델

### 5.1 현재 구현

현재 SQLite 버전 1에는 다음 테이블만 있다.

| 테이블 | 현재 상태 |
|---|---|
| orders | 완료, 단 계좌·환경·broker 식별 필드 보완 필요 |
| executions | 완료, AccountRef와 broker 체결 ID 정책 보완 필요 |
| anomaly_alerts | 완료 |

### 5.2 A가 추가할 금융 테이블

| 테이블 | 목적 | 우선순위 |
|---|---|---|
| order_events | 주문 상태 감사와 broker 이벤트 중복 제거 | 1차 |
| account_snapshots | 마지막으로 확인된 계좌 잔고 | 1차 |
| positions | account_snapshots에 포함된 보유 종목 | 1차 |
| sync_checkpoints | 재연결·재시작 후 REST 조정 시작점 | 1차 |
| candles | 차트 캐시와 오프라인 탐색 | 2차 |

orders에는 최소한 environment, account_key, client_request_id, broker_order_id를 추가한다.
client_request_id와 broker_order_id에는 계좌 범위의 unique 제약을 둔다. 금액은 REAL이
아닌 TEXT로 저장하고, Instant는 epoch millisecond INTEGER로 저장한다.

### 5.3 B가 저장할 로컬 상태

현재 B는 DesktopStateRepository와 PropertiesDesktopStateRepository로 아래 항목을 저장한다.

- 선택 종목
- 관심종목 그룹과 행
- 알림 규칙
- 알림 표시 목록
- 매매일지 표시 목록
- 접근성, 음성, 중복 주문 방지 설정

이 기능은 동작하지만 List<List<String>> 기반이므로 임시 구조다. App Key, App Secret,
토큰은 이 저장소에 절대 넣지 않는다.

1차에서는 properties 파일을 유지해도 된다. 대신 공개 저장 계약은 타입이 있는 모델을 사용한다.
SQLite로 옮길지는 데이터량과 동기화 요구가 생긴 뒤 결정한다. 내장 DB라는 이유만으로 UI 설정까지
금융 DB와 결합하지 않는다.

## 6. 개발자 A 구현 인터페이스

### A-1. SecurityQueryPort — 신규

목적: 종목 검색과 상세 조회를 키움 구현에서 분리한다.

~~~java
public interface SecurityQueryPort {
    List<SecuritySummary> search(String query, int limit);
    StockDetail getDetail(SecurityId security);
}
~~~

- 구현 위치: modules/kiwoom-adapter
- 기존 StockQueryPort는 현재 UI 호환용으로 유지한다.
- 검색 결과는 화면용 문자열 가격이 아니라 정규화된 숫자 모델을 반환한다.
- KRX와 NXT처럼 symbol이 같을 수 있으므로 SecurityId를 사용한다.

### A-2. CandleQueryPort — 보완

현재 String symbol 기반 인터페이스가 존재한다. 다음 목표 서명으로 바꾼다.

~~~java
public interface CandleQueryPort {
    List<Candle> getCandles(SecurityId security, CandleInterval interval, int count);
}
~~~

- 반환 순서는 과거에서 최신 순이다.
- count가 0 이하이면 검증 오류다.
- 분봉과 일봉을 같은 모델로 제공한다.
- 기존 호출부가 이동할 때까지 호환 어댑터를 둔다.

### A-3. AccountQueryPort — 신규

현재 AccountPort는 단일 Account만 동기식으로 반환한다. 실제 연동용 계약은 별도로 만든다.

~~~java
public interface AccountQueryPort {
    List<AccountRef> getAccounts(TradingEnvironment environment);
    AccountSnapshot getSnapshot(AccountRef account);
}
~~~

- 전체 계좌번호를 반환하지 않는다.
- 모의 계좌와 실전 계좌를 섞지 않는다.
- snapshot의 asOf를 실제 원본 기준 시각으로 기록한다.

### A-4. BrokerConnectionPort — 신규

목적: 토큰과 연결 수명주기를 B의 연결 화면과 분리한다.

~~~java
public interface BrokerConnectionPort {
    ConnectionSnapshot connect(TradingEnvironment environment);
    ConnectionSnapshot refresh();
    ConnectionSnapshot current();
    void disconnect();
}
~~~

- 자격증명은 어댑터 구성 단계에서 SecretStore를 통해 주입한다.
- ConnectionSnapshot에는 비밀 값이 아니라 상태, 안전한 설명, 만료 시각만 넣는다.
- refresh는 주문 전송이 아니므로 인증 정책에 따라 제한적으로 재시도할 수 있다.

### A-5. BrokerOrderPort — 신규

목적: 증권사 주문 전송 결과만 정규화한다. Order aggregate 상태 관리는 B의 Application
Service가 담당한다.

~~~java
public interface BrokerOrderPort {
    BrokerOrderResult submit(
            AccountRef account, String clientRequestId, OrderCommand command);
    BrokerOrderResult amend(
            AccountRef account, String clientRequestId,
            String brokerOrderId, AmendOrderCommand command);
    BrokerOrderResult cancel(
            AccountRef account, String clientRequestId, String brokerOrderId);
    List<BrokerOrderSnapshot> getOrders(
            AccountRef account, Instant fromInclusive);
    List<BrokerExecution> getExecutions(
            AccountRef account, Instant fromInclusive);
}
~~~

- submit, amend, cancel은 자동 재시도하지 않는다.
- 응답이 불명확하면 실패로 단정하지 않고 UNKNOWN을 반환한다.
- 기존 BrokerClient와 OrderLifecyclePort는 저수준 구현과 모의엔진 호환에 유지한다.

### A-6. MarketDataStreamPort — 보완

현재 인터페이스는 존재하지만 String symbol과 add/remove listener 방식이다.

~~~java
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
~~~

- 재연결 후 기존 구독을 자동 복구한다.
- 화면별 구독 해제를 EventSubscription으로 보장한다.
- 느린 화면 때문에 WebSocket 수신 루프가 멈추면 안 된다.

### A-7. BrokerTradingStreamPort — 신규

~~~java
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
~~~

- 주문·체결·잔고 이벤트는 합치거나 누락하지 않는다.
- UI와 TTS가 이 포트를 직접 구독하면 안 된다.
- 재연결 뒤 REST 조정이 끝난 후 동기화 완료 상태를 알린다.

### A-8. OrderRepository — 보완

현재 SQLite 구현까지 존재한다. 계좌와 broker 식별자를 찾는 메서드를 추가한다.

~~~java
public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(String localOrderId);
    Optional<Order> findByClientRequestId(
            AccountRef account, String clientRequestId);
    Optional<Order> findByBrokerOrderId(
            AccountRef account, String brokerOrderId);
    List<Order> findOpen(AccountRef account);
    List<Order> findRecent(AccountRef account, int limit);
    int deleteCreatedBefore(Instant cutoff);
}
~~~

### A-9. OrderEventRepository — 신규

~~~java
public interface OrderEventRepository {
    boolean append(OrderEvent event);
    List<OrderEvent> findByOrderId(String localOrderId);
}
~~~

같은 eventId를 다시 받으면 append는 false를 반환한다. 이것이 재연결 후 중복 체결 알림을
막는 근거가 된다.

### A-10. AccountSnapshotRepository — 신규

~~~java
public interface AccountSnapshotRepository {
    void save(AccountSnapshot snapshot);
    Optional<AccountSnapshot> findLatest(AccountRef account);
}
~~~

잔고와 positions는 한 트랜잭션으로 저장한다.

### A-11. SyncCheckpointRepository — 신규

~~~java
public interface SyncCheckpointRepository {
    Optional<SyncCheckpoint> find(
            AccountRef account, SyncResource resource);
    void save(SyncCheckpoint checkpoint);
}
~~~

SyncResource는 ORDERS, EXECUTIONS, BALANCE, POSITIONS를 포함한다.

### A-12. CredentialStorePort와 SecretStore — 보완

windows-secret-store에 다음 SecretStore 계약과 DPAPI 구현이 이미 있다.

~~~java
public interface SecretStore extends AutoCloseable {
    void store(String alias, char[] secret);
    Optional<char[]> load(String alias);
    void delete(String alias);
    boolean contains(String alias);
    Set<String> aliases();
    boolean isHardwareBacked();
    String description();
    @Override void close();
}
~~~

다만 modules/application의 연결 서비스가 windows-secret-store에 역으로 의존하면 안 된다.
따라서 같은 메서드를 가진 CredentialStorePort 계약을 modules/application에 두고,
windows-secret-store의 FileSecretStore가 이를 구현하도록 보완한다. 기존 SecretStore는
마이그레이션 기간에 CredentialStorePort를 확장해도 된다.

B는 파일 경로나 DPAPI 클래스를 직접 다루지 않고 composition root에서 구현체를 연결한다.
로드한 char 배열은 사용 직후 0으로 덮어쓴다.

### A-13. 시장 탐색 조회 Port — 2차 신규

스캐너, 조건검색, 수급, 시장 화면을 실제 데이터로 바꿀 때 아래 읽기 전용 포트를 추가한다.
핵심 주문 연동 전에는 Fake 구현으로 진행할 수 있다.

~~~java
public interface MarketOverviewQueryPort {
    MarketOverview getOverview(MarketId market);
}

public interface MarketRankingQueryPort {
    List<RankingResult> rank(RankingCriteria criteria, int limit);
}

public interface ConditionSearchPort {
    List<ConditionDefinition> conditions(AccountRef account);
    List<SecuritySummary> execute(
            AccountRef account, String conditionId, int limit);
}

public interface InvestorFlowQueryPort {
    List<InvestorFlow> getInvestorFlow(
            SecurityId security, Instant fromInclusive);
}
~~~

키움 API에서 제공하지 않는 필드는 화면에서 임의 생성하지 않고 unavailable 상태로 표시한다.

## 7. 개발자 B 구현 인터페이스

### B-1. BrokerConnectionApplicationPort — 신규

현재 ConnectionViewModel은 실제 API를 호출하지 않는 데모다. 화면은 이 포트만 호출한다.

~~~java
public interface BrokerConnectionApplicationPort {
    CompletionStage<ConnectionSnapshot> connect(
            TradingEnvironment environment,
            char[] appKey,
            char[] appSecret);
    CompletionStage<ConnectionSnapshot> refresh();
    CompletionStage<Void> disconnect();
    CompletionStage<ConnectionSnapshot> status();
}
~~~

- 서비스는 자격증명을 SecretStore에 저장하고 입력 배열을 즉시 지운다.
- 화면에는 토큰 문자열을 반환하지 않는다.
- 실전 연결은 화면에 모의 연결과 명확히 다른 문구와 상태를 제공한다.

### B-2. MarketApplicationPort — 신규

~~~java
public interface MarketApplicationPort {
    CompletionStage<List<SecuritySummary>> search(
            String query, int limit);
    CompletionStage<StockDetail> loadDetail(SecurityId security);
    CompletionStage<List<Candle>> loadCandles(
            SecurityId security, CandleInterval interval, int count);
    EventSubscription monitor(
            SecurityId security, MarketApplicationListener listener);
}

public interface MarketApplicationListener {
    void onQuote(Quote quote);
    void onConnectionChanged(
            ConnectionState state, String safeDetail);
}
~~~

- 현재 StockSearchViewModel의 고정 목록을 이 포트 결과로 교체한다.
- 현재 StockDetailViewModel이 화면 문자열로 가격을 다시 만드는 로직을 제거한다.
- Sonification과 시각 차트는 같은 Candle 목록을 사용한다.

### B-3. AccountApplicationPort — 신규

~~~java
public interface AccountApplicationPort {
    CompletionStage<List<AccountRef>> accounts(
            TradingEnvironment environment);
    CompletionStage<Optional<AccountSnapshot>> latest(AccountRef account);
    CompletionStage<AccountSnapshot> refresh(AccountRef account);
}
~~~

- latest는 네트워크를 호출하지 않고 마지막 저장값을 반환한다.
- refresh는 A에서 새 값을 받은 뒤 저장하고 반환한다.
- 오래된 상태는 최신처럼 표시하지 않고 asOf와 freshness를 화면·음성에 제공한다.

### B-4. TradingApplicationPort — 신규

~~~java
public interface TradingApplicationPort {
    ConfirmableOrderPreview previewNew(
            AccountRef account, OrderCommand command);
    ConfirmableOrderPreview previewAmend(
            AmendOrderCommand command);
    ConfirmableOrderPreview previewCancel(
            CancelOrderCommand command);
    CompletionStage<Order> submitConfirmed(String previewId);
    CompletionStage<List<Order>> openOrders(AccountRef account);
    CompletionStage<List<Order>> recentOrders(
            AccountRef account, int limit);
    EventSubscription observe(
            AccountRef account, TradingApplicationListener listener);
}
~~~

- 모든 실거래 신규·정정·취소에 재확인을 적용한다.
- previewId는 일회용이며 만료 시각을 가진다.
- 같은 previewId의 중복 제출을 거부한다.
- A의 BrokerOrderPort를 호출하는 유일한 사용자 주문 진입점이다.

### B-5. TradingApplicationListener — 신규

~~~java
public interface TradingApplicationListener {
    void onOrderEvent(OrderEvent event);
    void onExecution(Execution execution);
    void onAccountChanged(AccountSnapshot snapshot);
    void onConnectionChanged(
            ConnectionState state, String safeDetail);
}
~~~

A의 원본 이벤트를 중복 제거하고 DB commit한 뒤에만 이 리스너로 발행한다. B는 여기서 화면
상태와 SpeechQueue 알림을 함께 만든다.

### B-6. MarketDiscoveryApplicationPort — 2차 신규

~~~java
public interface MarketDiscoveryApplicationPort {
    CompletionStage<MarketOverview> overview(MarketId market);
    CompletionStage<List<RankingResult>> rank(
            RankingCriteria criteria, int limit);
    CompletionStage<List<ConditionDefinition>> conditions(
            AccountRef account);
    CompletionStage<List<SecuritySummary>> executeCondition(
            AccountRef account, String conditionId, int limit);
    CompletionStage<List<InvestorFlow>> investorFlow(
            SecurityId security, Instant fromInclusive);
}
~~~

ScannerViewModel의 고정 ScannerItem 목록과 시장·조건·수급·미국주식 화면의 샘플 행을 이
포트 결과로 교체한다.

### B-7. DesktopStateRepository — 완료, 모델 보완

현재 계약:

~~~java
public interface DesktopStateRepository {
    Optional<DesktopStateSnapshot> load();
    void save(DesktopStateSnapshot snapshot);
}
~~~

properties 구현과 원자적 저장은 유지한다. 다음 수정에서 List<List<String>>을
WatchlistItem, AlertRule, JournalEntry 같은 record 목록으로 바꾼다. 손상된 파일은 안전한
기본값으로 복구하고 비밀정보는 저장하지 않는다.

### B-8. WatchlistRepository — 신규

~~~java
public interface WatchlistRepository {
    List<WatchlistGroup> groups();
    List<WatchlistItem> items();
    void saveGroups(List<WatchlistGroup> groups);
    void saveItems(List<WatchlistItem> items);
}
~~~

현재 WatchlistViewModel의 추가·수정·삭제 동작은 완료되어 있다. 저장 모델을 문자열 행에서
typed record로 바꾼 뒤 이 포트에 연결한다.

### B-9. AccessibilityPreferencesRepository — 신규

~~~java
public interface AccessibilityPreferencesRepository {
    AccessibilityPreferences load();
    void save(AccessibilityPreferences preferences);
}
~~~

현재 DesktopStateSnapshot 안의 큰 글자, 고대비, 음성, 키보드 안내 설정을 이 모델로
이동한다. 값이 없거나 손상돼도 null 대신 안전한 기본값을 반환한다.

### B-10. SonificationPreferencesRepository — 신규

~~~java
public interface SonificationPreferencesRepository {
    SonificationPreferences load();
    void save(SonificationPreferences preferences);
}
~~~

그래프 음량, 자동/고정 음역, 재생 속도, 기준점 안내 간격을 저장한다. 그래프 매핑과 오디오
출력 알고리즘은 sonification 모듈에 계속 둔다.

### B-11. SpeechPort, SoundPort, SonificationPort — 완료

세 포트는 역할이 이미 분리되어 있으므로 합치지 않는다.

- SpeechPort: 문장 TTS
- SoundPort: 짧은 의미 상태음
- SonificationPort: 시계열 그래프 오디오 프레임

OS별 TTS 차이는 SpeechAdapterFactory가 선택하고, 우선순위·중복 제거는 SpeechQueue가
담당한다.

### B-12. 화면 내부 구조 — 부분 완료

- Screen과 DesktopScreenController: 완료
- Connection/Search/Watchlist/Scanner 화면 View 분리: 완료
- StockSearch/StockDetail/Watchlist/Scanner ViewModel: 완료, 실제 Application Port 연결 필요
- DesktopSession과 로컬 자동 저장: 완료, typed 모델 보완 필요
- 나머지 화면의 View/ViewModel 분리: 진행 필요
- 내비게이션용 별도 공개 인터페이스: 만들지 않음

DesktopScreenController는 데스크톱 앱 내부 구현이다. 플랫폼을 추가하지 않는 현재 단계에서
Navigator 같은 공개 Port를 추가하면 추상화만 늘어나므로 필요하지 않다.

## 8. 화면별 실제 연동 대상

| 화면 | B가 호출할 계약 | A가 구현할 계약 | 현재 상태 |
|---|---|---|---|
| API 연결 | BrokerConnectionApplicationPort | BrokerConnectionPort, SecretStore | 데모 |
| 종목 검색 | MarketApplicationPort | SecurityQueryPort | 고정 목록 |
| 종목 상세·차트 | MarketApplicationPort | SecurityQueryPort, CandleQueryPort, MarketDataStreamPort | 일부 Fake |
| 관심종목 | WatchlistRepository, MarketApplicationPort | MarketDataStreamPort | 로컬 동작 |
| 랭킹·스캐너 | MarketDiscoveryApplicationPort | MarketRankingQueryPort | 고정 목록 |
| 조건검색 | MarketDiscoveryApplicationPort | ConditionSearchPort | 샘플 화면 |
| 수급 | MarketDiscoveryApplicationPort | InvestorFlowQueryPort | 샘플 화면 |
| 시장·미국주식 | MarketDiscoveryApplicationPort | MarketOverviewQueryPort | 샘플 화면 |
| 주문 | TradingApplicationPort | BrokerOrderPort, BrokerTradingStreamPort | 모의엔진 동작 |
| 계좌 | AccountApplicationPort | AccountQueryPort, AccountSnapshotRepository | 모의 데이터 |
| 알림 | TradingApplicationListener, SpeechQueue | BrokerTradingStreamPort | 샘플+로컬 |
| 청각 차트 | MarketApplicationPort, SonificationPort | CandleQueryPort, MarketDataStreamPort | Fake 데이터로 동작 |
| 설정 | 접근성·Sonification 설정 Repository | 없음 | 로컬 저장 동작 |
| 대시보드 | 위 Application Port들의 읽기 조합 | 해당 조회 Port | 샘플 중심 |

## 9. 지금 코드에서 바로 고쳐야 하는 지점

1. StockSearchViewModel 안의 종목 목록을 제거하고 MarketApplicationPort를 주입한다.
2. ScannerViewModel 안의 ScannerItem 목록을 제거하고 MarketDiscoveryApplicationPort를 주입한다.
3. ConnectionViewModel의 성공 문구만 바꾸는 데모 메서드를 BrokerConnectionApplicationPort 호출로 바꾼다.
4. StockDetailViewModel이 표시 문자열을 숫자로 역파싱하고 임의 가격을 만드는 로직을 제거한다.
5. DesktopSession의 List<List<String>>을 typed record로 바꾼다.
6. DesktopApplication은 composition root 역할만 남기고 나머지 화면 생성 코드를 View 클래스로 옮긴다.
7. JavaFX가 InMemoryMockTradingAdapter와 FakeStockQueryAdapter를 직접 사용하는 부분은
   Application Port 구현을 주입하도록 바꾼다. 단, composition root에서 mock/live 구현을
   선택하는 것은 허용한다.
8. Gradle 패키징 작업은 코드가 있으나 packagePortable 실행 검증이 끝나지 않았다.
   Windows installer는 WiX 설치 환경에서 별도 검증한다.

## 10. 구현 순서

### 공동

1. TradingEnvironment, Exchange, SecurityId, AccountRef, EventSubscription 확정
2. 주문 outcome, OrderEvent, AccountSnapshot, 오류 코드 확정
3. 공개 Port PR을 먼저 병합

### 개발자 A

1. SecurityQueryPort, AccountQueryPort, BrokerConnectionPort
2. CandleQueryPort와 MarketDataStreamPort 보완
3. BrokerOrderPort, BrokerTradingStreamPort와 Fake 구현
4. OrderRepository 보완과 SQLite 신규 테이블
5. 재연결·REST reconciliation
6. 2차 시장 탐색 조회 Port

### 개발자 B

1. BrokerConnectionApplicationPort와 MarketApplicationPort
2. 검색·상세·차트 ViewModel에서 고정 데이터 제거
3. AccountApplicationPort와 TradingApplicationPort
4. 주문·체결 이벤트를 화면, SpeechQueue, SoundPort에 연결
5. typed DesktopState와 설정 Repository
6. 스캐너·조건·수급 화면의 MarketDiscoveryApplicationPort 연결
7. 접근성 회귀 테스트와 패키징 검증

## 11. 완료 기준

### A 완료 기준

- Fake와 Kiwoom 구현이 같은 Port 계약 테스트를 통과한다.
- 주문 POST 타임아웃이 중복 주문을 만들지 않는다.
- WebSocket 재연결 후 구독과 주문·체결 누락을 복구한다.
- 앱 재시작 후 SQLite 기준으로 미체결 주문을 조정한다.
- 로그와 예외에 계좌번호, App Secret, Token이 나오지 않는다.

### B 완료 기준

- ViewModel 테스트가 Kiwoom 구현 없이 Fake Application Port로 통과한다.
- 검색, 상세, 차트, 계좌, 주문 화면에 임의 가격 생성 코드가 없다.
- 실거래 주문은 미리보기와 명시적 재확인 없이는 제출되지 않는다.
- 체결·거부·연결 끊김이 화면 텍스트와 음성 모두로 전달된다.
- 키보드만으로 주문 확인과 취소가 가능하다.
- TTS 또는 오디오 실패가 주문 처리와 화면 표시를 막지 않는다.

## 12. 합의 체크리스트

- [ ] A/B 담당 범위에 동의
- [ ] 공통 모델 이름과 필드에 동의
- [ ] 기존 호환 Port와 신규 Port의 병행 기간에 동의
- [ ] 모의/실전 데이터와 비밀 저장 분리에 동의
- [ ] UNKNOWN 주문과 자동 재시도 금지에 동의
- [ ] SQLite 버전 2 테이블과 필드에 동의
- [ ] B의 Application Port가 UI의 유일한 금융 진입점이라는 데 동의
- [ ] 시장 탐색 기능은 핵심 주문 연동 뒤 2차로 진행하는 데 동의
- [ ] 공개 계약 변경 시 A/B 리뷰를 필수로 하는 데 동의

~~~text
개발자 A: ____________________  날짜: __________
개발자 B: ____________________  날짜: __________
~~~
