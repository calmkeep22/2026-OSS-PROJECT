# 금융·접근성 통합 계약서

> 상태: 합의 전 초안
> 대상: 개발자 A(금융·증권 연동), 개발자 B(Application·UI·접근성)
> 계약 버전: 0.2.0
> 최종 수정일: 2026-08-10

## 1. 목적

이 문서는 키움 REST API와 WebSocket, SQLite, JavaFX, TTS, Sonification을 연결할 때
개발자 A와 B가 서로의 구현을 기다리지 않고 개발할 수 있도록 모듈 경계와 코드 계약을
고정한다.

인터페이스별 메서드, 입출력, 구현 규칙과 완료 조건은
[A/B 인터페이스별 구현 명세](A-B-INTERFACE-SPEC.md)를 따른다.

이 문서에서 사용하는 용어의 강도는 다음과 같다.

- **MUST**: 반드시 지켜야 하며, 위반 시 계약 변경 합의가 필요하다.
- **SHOULD**: 특별한 이유가 없다면 지킨다. 다르게 구현하면 PR에 이유를 기록한다.
- **MAY**: 구현체가 선택할 수 있다.

## 2. 책임과 소유권

### 2.1 개발자 A

개발자 A는 다음 영역의 구현과 테스트를 소유한다.

- 키움 OAuth, REST, WebSocket 프로토콜
- 종목 정보, 시세, 차트, 계좌, 잔고 조회
- 주문 제출·정정·취소와 주문 상태 전이
- 키움 오류를 애플리케이션 오류로 변환
- 재접속, 호출 제한, 재동기화, 장애 복구
- 금융 데이터 SQLite 마이그레이션과 저장소
- `broker-api`, `kiwoom-adapter`, `persistence-sqlite`의 금융 영역

### 2.2 개발자 B

개발자 B는 다음 영역의 구현과 테스트를 소유한다.

- Application Use Case와 JavaFX 화면
- 키보드 탐색, 스크린리더, 큰 글자, 고대비
- 주문 미리보기와 명시적 재확인 UX
- TTS 우선순위·중복 제거·중단 정책
- Sonification 재생·탐색·설정
- 연결, 주문, 체결, 오류 이벤트의 접근 가능한 표현
- 접근성·Sonification 설정 저장소

### 2.3 공동 소유

다음 코드는 A와 B의 공동 계약이므로 한 명이 단독으로 호환성을 깨면 안 된다.

- `modules/finance-domain`의 공개 모델과 enum
- `modules/application`의 공개 Port와 이벤트 Listener
- 주문 상태 전이 규칙
- 오류 코드와 사용자 메시지 계약
- `persistence-sqlite`의 마이그레이션 순서

## 3. 금지된 의존성

의존성 방향은 아래 규칙을 MUST 준수한다.

```text
desktop-javafx ──> application ──> finance-domain
       │                 ▲
       ├─> accessibility │
       └─> sonification  │
                         │
kiwoom-adapter ──────────┤
mock-trading ────────────┤
persistence-sqlite ──────┘
```

- `finance-domain`은 JavaFX, SQLite, 키움, TTS를 참조하지 않는다.
- `application`은 JavaFX와 키움 구현 클래스를 참조하지 않는다.
- `kiwoom-adapter`는 JavaFX, `SpeechPort`, `SonificationPort`를 참조하지 않는다.
- `desktop-javafx`는 `KiwoomRestClient` 같은 어댑터를 화면 Controller에서 직접 호출하지 않는다.
- A는 음성을 직접 출력하지 않고 의미 있는 도메인 이벤트를 발행한다.
- B는 키움 TR 코드나 JSON 필드명을 해석하지 않는다.

## 4. 데이터 원본과 처리 흐름

### 4.1 원본 규칙

- 실제 계좌·주문·체결 상태의 최종 원본은 키움증권이다.
- SQLite는 로컬 캐시, 감사 이력, 재시작 복구용이다.
- 화면 메모리는 현재 표시 상태이며 영속 원본이 아니다.
- 접근성 설정은 SQLite가 원본이다.

### 4.2 표준 흐름

```text
앱 시작
  -> SQLite의 마지막 정상 상태 로딩
  -> REST로 종목·계좌·미체결·체결·잔고 재조회
  -> SQLite와 키움 상태 조정(reconciliation)
  -> WebSocket LOGIN 및 REG
  -> A가 실시간 시세 0B/0D, 주문 00, 잔고 04를 정규화
  -> Application Service가 중복 제거·상태 전이·SQLite 저장
  -> B용 Application Listener로 UI·접근성 기능에 통지
```

- REST는 초기 조회와 복구에 사용한다.
- WebSocket은 연결 이후의 변경분에 사용한다.
- WebSocket 재연결만으로 누락 데이터가 없다고 가정하면 안 된다.
- 재연결 후 주문·체결·잔고는 REST로 다시 조정해야 한다.

## 5. 실행환경 계약

다음 enum을 `finance-domain`에 추가한다.

```java
package org.ossproject.finance.model;

public enum TradingEnvironment {
    LOCAL_SIMULATION,
    KIWOOM_MOCK,
    KIWOOM_REAL;

    public boolean isRealMoney() {
        return this == KIWOOM_REAL;
    }
}
```

규칙:

- 실행환경별 DB 파일을 분리한다.
- 권장 파일명은 `local-simulation.db`, `kiwoom-mock.db`, `kiwoom-real.db`다.
- B는 모든 주문 재확인 화면에서 실행환경을 텍스트와 음성으로 알린다.
- `KIWOOM_REAL`은 명시적 사용자 확인 없이는 제출할 수 없다.
- 실행환경 전환 시 기존 WebSocket 구독과 화면의 주문 입력을 초기화한다.
- 테스트 기본값은 반드시 `LOCAL_SIMULATION` 또는 `KIWOOM_MOCK`이다.

## 6. 공통 식별자와 값 타입

### 6.1 거래소와 종목

종목코드 문자열만으로 종목을 식별하면 안 된다. 다음 타입을 추가한다.

```java
package org.ossproject.finance.model;

public enum Exchange {
    KRX,
    NXT,
    SOR
}
```

```java
package org.ossproject.finance.model;

public record SecurityId(String symbol, Exchange exchange) {
    public SecurityId {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (exchange == null) {
            throw new IllegalArgumentException("거래소는 필수입니다.");
        }
        symbol = symbol.trim();
    }
}
```

### 6.2 계좌 참조

계좌번호가 로그나 UI로 무분별하게 전파되지 않도록 다음 타입을 추가한다.

```java
package org.ossproject.finance.model;

public record AccountRef(
        String accountId,
        String maskedAccountNo,
        String alias,
        TradingEnvironment environment
) {
}
```

- `accountId`는 로컬 UUID이며 공개 계약에서 계좌번호 대신 사용한다.
- 전체 계좌번호는 A의 어댑터와 보안 저장 영역 밖으로 노출하지 않는다.
- App Key, Secret, 토큰, 계좌 비밀번호는 도메인 모델에 넣지 않는다.

### 6.3 공통 데이터 타입

- 금액과 가격은 `BigDecimal`을 MUST 사용한다. `double`을 사용하지 않는다.
- 수량과 누적 거래량은 `long`을 사용한다.
- 도메인 시각은 UTC 기준 `Instant`를 사용한다.
- B가 화면에 표시할 때만 `Asia/Seoul` 등의 지역 시간대로 변환한다.
- 외부 ID는 숫자로 보이더라도 `String`으로 저장한다.
- 공개 DTO에 키움 JSON 원문이나 `Map<String, Object>`를 사용하지 않는다.

## 7. 주문 안전 계약

### 7.1 주문 상태

`OrderStatus`의 목표 상태는 다음과 같다.

```java
public enum OrderStatus {
    NEW,
    SUBMITTING,
    ACCEPTED,
    PARTIALLY_FILLED,
    CANCEL_REQUESTED,
    FILLED,
    CANCELLED,
    REJECTED,
    UNKNOWN
}
```

`CONFIRMING`은 UI 상태이며 주문 도메인 상태에 넣지 않는다. 주문은 사용자가 확인한 후에만
생성한다.

허용 전이는 다음과 같다.

```text
NEW -> SUBMITTING
SUBMITTING -> ACCEPTED | REJECTED | UNKNOWN
ACCEPTED -> PARTIALLY_FILLED | FILLED | CANCEL_REQUESTED | REJECTED
PARTIALLY_FILLED -> PARTIALLY_FILLED | FILLED | CANCEL_REQUESTED
CANCEL_REQUESTED -> CANCELLED | PARTIALLY_FILLED | FILLED | UNKNOWN
UNKNOWN -> ACCEPTED | PARTIALLY_FILLED | FILLED | CANCELLED | REJECTED
```

- `FILLED`, `CANCELLED`, `REJECTED`는 최종 상태다.
- `SUBMITTING`, `UNKNOWN` 주문은 자동으로 다시 제출하면 안 된다.
- `UNKNOWN`은 REST 미체결·체결 조회로 조정한 후에만 다른 상태로 변경한다.
- 같은 `brokerExecutionId`는 한 번만 반영한다.

### 7.2 주문 명령의 목표 형태

현재 `OrderCommand`의 `symbol` 문자열은 `SecurityId`로 교체한다. 목표 생성자 계약은
다음과 같다.

```java
package org.ossproject.finance.model;

import java.math.BigDecimal;

public record OrderCommand(
        SecurityId security,
        String securityName,
        OrderSide side,
        OrderType type,
        long quantity,
        BigDecimal limitPrice
) {
}
```

`Quote`, `Position`, `Order`도 공개 계약에서는 개별 `symbol` 대신 `SecurityId`를 가져야 한다.
기존 UI 마이그레이션 동안에는 `symbol()` 호환 메서드를 제공할 수 있지만 신규 Port에는
`String symbol`을 추가하지 않는다.

`Order`에는 최소한 다음 식별자가 함께 있어야 한다.

```text
localOrderId     앱에서 생성한 주문 ID, 항상 존재
brokerOrderId    키움 접수 후 받은 주문번호, 접수 전에는 null 가능
clientRequestId  제출 시도 식별자, 항상 존재
account          AccountRef
security         SecurityId
```

### 7.3 주문 미리보기와 확인

B가 입력값을 확인한 뒤 A가 다른 값으로 주문하지 못하도록 미리보기 ID 기반 계약을 사용한다.

```java
package org.ossproject.finance.model;

import java.math.BigDecimal;
import java.time.Instant;

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

```java
package org.ossproject.finance.model;

public enum OrderOperation {
    NEW,
    AMEND,
    CANCEL
}
```

정정과 취소 입력은 문자열 인자 묶음 대신 불변 명령으로 전달한다.

```java
public record AmendOrderCommand(
        String localOrderId,
        long newQuantity,
        BigDecimal newLimitPrice
) {
}

public record CancelOrderCommand(String localOrderId) {
}
```

실제 제출을 포함하는 `TradingApplicationPort`는 JavaFX 스레드를 막지 않도록 비동기로 설계하며
9.3절에 정의한다.

규칙:

- `previewId`는 일회용이며 기본 유효시간은 30초다.
- 미리보기 이후 수량·가격·계좌·종목·작업 종류가 바뀌면 새 미리보기를 발급한다.
- `submitConfirmed`는 동일 `previewId`로 두 번 성공할 수 없다.
- 음성 명령만으로 `submitConfirmed`를 호출하지 않는다.
- B는 종목명·거래소·매수/매도·유형·수량·가격·예상 금액·환경을 재확인한다.
- 실전 신규·정정·취소 주문은 모두 각각의 미리보기와 명시적 확인을 거친다.

## 8. A가 구현할 Outbound Port

이 절의 Port 정의는 `modules/application`에 두고 A가 키움·SQLite 어댑터로 구현한다.
네트워크 Port는 동기식이어도 되지만 B가 직접 호출하지 않는다. B의 Application Service가
작업 스레드에서 호출하고 9절의 비동기 API로 감싼다.

### 8.1 A의 최소 필수 목록

| 인터페이스 | 필요성 | 이유 |
|---|---|---|
| `SecurityQueryPort` | MUST | 종목 검색과 상세 조회를 키움 구현에서 분리 |
| `CandleQueryPort` | MUST | 차트와 그래프 소리의 동일한 봉 데이터 계약 |
| `AccountQueryPort` | MUST | 계좌·잔고·보유종목의 명시적 동기화 |
| `BrokerOrderPort` | MUST | 신규·정정·취소와 조회를 도메인 상태 관리에서 분리 |
| `MarketDataStreamPort` | MUST | 유실 가능한 최신 시세 스트림 |
| `BrokerTradingStreamPort` | MUST | 유실하면 안 되는 주문·체결·잔고 스트림 |
| `OrderRepository` | MUST | 앱 재시작과 결과 불명 주문 복구 |
| `OrderEventRepository` | MUST | 주문 이벤트 중복 제거와 감사 추적 |
| `AccountSnapshotRepository` | MUST | 주문 전 계좌 신선도 확인과 오프라인 표시 |
| `SyncCheckpointRepository` | MUST | 재연결 뒤 REST/WebSocket 재동기화 기준 |
| `CandleRepository` | PHASE 2 | 오프라인 차트·재생 캐시가 필요할 때 추가 |

조회, 주문, 시세, 거래 이벤트를 하나의 `BrokerPort`로 합치지 않는다. 연결 수명과 오류·재시도
정책이 서로 다르기 때문이다.

### 8.2 조회 Port

```java
public interface SecurityQueryPort {
    List<SecuritySummary> search(String query, int limit);
    StockDetail getDetail(SecurityId security);
}

public interface CandleQueryPort {
    List<Candle> getCandles(SecurityId security, CandleInterval interval, int count);
}

public interface AccountQueryPort {
    List<AccountRef> getAccounts(TradingEnvironment environment);
    AccountSnapshot getSnapshot(AccountRef account);
}
```

- `SecuritySummary`는 `SecurityId security`, `String name`을 가진 불변 record다.
- 봉은 오래된 순에서 최신 순으로 반환하며 `count <= 0`은 거부한다.
- 키움 연속조회 키와 원본 필드명은 어댑터 밖으로 노출하지 않는다.
- `AccountSnapshot`은 `AccountRef`, 잔고, 보유종목, `asOf`를 포함한다.

### 8.3 주문 Port

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

`BrokerOrderPort`는 완성된 도메인 `Order`를 반환하지 않는다. A는 증권사 응답만 정규화하고,
B의 `TradingApplicationService`가 로컬 ID·상태 전이·저장을 책임진다.

- 변경 요청에는 일반 조회용 자동 재시도를 절대 적용하지 않는다.
- 타임아웃처럼 성공 여부가 불명확하면 예외로 성공·실패를 단정하지 않고 `UNKNOWN`을 반환한다.
- `brokerOrderId`는 `UNKNOWN` 또는 접수 전 응답에서 `null`일 수 있다.
- `getOrders`와 `getExecutions`는 시작·재연결 시 로컬 DB를 조정하기 위한 조회다.

### 8.4 구독 수명 계약

```java
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}
```

`close()`는 여러 번 호출해도 안전해야 한다. 리스너 등록 메서드는 반드시 이 핸들을 반환하여
화면 종료·종목 변경 때 구독을 해제할 수 있게 한다.

### 8.5 실시간 시세

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
```

`Quote`는 `SecurityId`, 가격·호가·거래량, 거래소 시각 `occurredAt`, 앱 수신 시각
`receivedAt`을 제공한다. 연결 복구 후 구독을 다시 등록하며, 느린 소비자를 위해 중간 시세를
최신값으로 합칠 수 있다.

### 8.6 주문·체결·잔고 실시간 이벤트

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

세 이벤트는 키움 원문 Map이 아닌 타입이 지정된 정규화 record다. 이 Listener는
`TradingApplicationService`만 소비한다. JavaFX나 TTS가 직접 구독하면 DB 저장·중복 제거·
REST 재동기화를 우회하므로 금지한다.

Application Service는 정규화 이벤트를 도메인 `OrderEvent`, `Execution`, `AccountSnapshot`으로
변환해 저장한 뒤 9.4절의 B용 Listener에 전달한다.

- 주문·체결 이벤트는 합치거나 버리지 않는다.
- `eventId`와 `brokerExecutionId`로 중복 제거한다.
- 이벤트 순서는 로컬 주문 단위로 보장한다.
- 한 Listener의 예외가 WebSocket 수신 루프를 종료시키면 안 된다.

## 9. B가 구현할 Inbound Port와 접근성 Port

B는 아래 Application Port의 서비스 구현과 JavaFX·음성·상태음·소니피케이션 어댑터를
소유한다. 네트워크 가능성이 있는 메서드는 `CompletionStage`를 반환하여 JavaFX Application
Thread를 막지 않는다.

### 9.1 B의 최소 필수 목록

| 인터페이스 | 필요성 | 이유 |
|---|---|---|
| `MarketApplicationPort` | MUST | 화면이 키움 조회·시세 구현을 직접 알지 않게 함 |
| `AccountApplicationPort` | MUST | 캐시 조회와 원격 새로고침을 구분 |
| `TradingApplicationPort` | MUST | 주문 확인·멱등성·상태 저장의 단일 진입점 |
| `MarketApplicationListener` | MUST | UI와 소니피케이션에 정제된 시세·연결 상태 전달 |
| `TradingApplicationListener` | MUST | 저장·정합성 처리 후 주문·체결·잔고 전달 |
| `EventSubscription` | MUST | 화면 수명에 맞춘 안전한 구독 해제 |
| `AccessibilityPreferencesRepository` | MUST | TTS·큰 글자·고대비 설정 유지 |
| `SonificationPreferencesRepository` | MUST | 사용자별 음역·볼륨·정규화 설정 유지 |
| `WatchlistRepository` | PHASE 2 | 관심종목 기능이 확정될 때 추가 |
| 기존 `SpeechPort`, `SoundPort`, `SonificationPort` | KEEP | 이미 플랫폼 출력 경계가 분리되어 있음 |

### 9.2 시장과 계좌 Application Port

```java
public interface MarketApplicationPort {
    CompletionStage<List<SecuritySummary>> search(String query, int limit);
    CompletionStage<StockDetail> loadDetail(SecurityId security);
    CompletionStage<List<Candle>> loadCandles(
            SecurityId security, CandleInterval interval, int count);
    EventSubscription monitor(SecurityId security, MarketApplicationListener listener);
}

public interface MarketApplicationListener {
    void onQuote(Quote quote);
    void onConnectionChanged(ConnectionState state, String safeDetail);
}

public interface AccountApplicationPort {
    CompletionStage<List<AccountRef>> accounts(TradingEnvironment environment);
    CompletionStage<Optional<AccountSnapshot>> latest(AccountRef account);
    CompletionStage<AccountSnapshot> refresh(AccountRef account);
}
```

`latest`는 SQLite의 마지막 스냅샷만 읽는다. 주문 허용 여부는 데이터 존재 여부가 아니라
`asOf`와 마지막 동기화 성공 시각으로 판단한다.

### 9.3 주문 Application Port

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

- 미리보기는 최근 로컬 계좌 스냅샷만 사용하므로 동기식이어도 된다. 신선하지 않으면
  `ACCOUNT_SYNC_REQUIRED`로 거부하고 UI가 `refresh`를 호출한다.
- `submitConfirmed`만 A의 주문 Port를 호출하며 JavaFX 스레드 밖에서 실행한다.
- 주문 결과가 불명확해도 실패 예외로 버리지 않고 `OrderStatus.UNKNOWN`인 `Order`를 반환한다.
- SQLite 조회도 I/O이므로 `latest`, `openOrders`, `recentOrders`는 JavaFX 스레드 밖에서 실행한다.

### 9.4 정합성 처리 후 이벤트

```java
public interface TradingApplicationListener {
    void onOrderEvent(OrderEvent event);
    void onExecution(Execution execution);
    void onAccountChanged(AccountSnapshot snapshot);
    void onConnectionChanged(ConnectionState state, String safeDetail);
}
```

이 Listener에는 저장과 중복 제거가 끝난 이벤트만 온다. B는 콜백에서 JavaFX Node를 바로
변경하지 않고 `Platform.runLater`를 사용한다.

### 9.5 접근성 알림 정책

B는 도메인 이벤트를 다음 우선순위로 변환한다.

```text
CRITICAL     실전 주문 결과 불명, 치명적 보안·계좌 오류
ORDER        주문 접수, 부분 체결, 전량 체결, 취소, 거부
CONNECTION   연결 끊김, 재연결, 시세 지연, 복구
ALERT        급등락, 거래량 이상, VI
USER_REQUEST 사용자가 요청한 현재가·차트 설명
INFORMATION  일반 상태 안내
```

- WebSocket 시세 한 틱마다 TTS를 생성하지 않는다.
- 화면 현재가는 최대 초당 4회 갱신하고 Sonification은 최신 시세를 자체 샘플링한다.
- 주문·체결 이벤트는 버리거나 최신값으로 합치지 않는다.
- 모든 음성 정보는 화면 텍스트로도 제공하고 색상만으로 상태를 표현하지 않는다.

권장 `SpeechRequest.deduplicationKey`는 다음과 같다.

```text
order:{localOrderId}:{status}:{filledQuantity}
execution:{executionId}
connection:{source}:{state}
anomaly:{security}:{type}:{timeBucket}
quote-request:{security}:{requestedAt}
```

## 10. 연결과 데이터 신선도

`ConnectionState`에 `STALE`을 추가한다.

```java
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    STALE,
    FAILED
}
```

- `CONNECTED`: WebSocket 연결과 로그인이 완료된 상태
- `STALE`: 연결은 있으나 설정된 시간 동안 기대한 데이터가 없는 상태
- 실전 주문은 연결만으로 허용하지 않고 최근 계좌 동기화 성공 여부도 확인한다.
- `STALE`, `DISCONNECTED`, `RECONNECTING`, `FAILED`에서는 실전 주문을 차단한다.
- B는 연결 끊김과 정상 복구를 각각 한 번 이상 음성·텍스트로 알린다.
- 연결 상세 메시지에는 토큰, 전체 계좌번호, 키움 원문 응답을 넣지 않는다.

## 11. 오류 계약

키움 오류 코드와 메시지를 UI에 직접 노출하지 않는다. 다음 공통 코드를 추가한다.

```java
package org.ossproject.application.error;

public enum ApplicationErrorCode {
    VALIDATION_ERROR,
    AUTH_REQUIRED,
    CONNECTION_UNAVAILABLE,
    STALE_DATA,
    RATE_LIMITED,
    ACCOUNT_SYNC_REQUIRED,
    INSUFFICIENT_CASH,
    INSUFFICIENT_POSITION,
    ORDER_REJECTED,
    ORDER_RESULT_UNKNOWN,
    DATA_NOT_FOUND,
    INTERNAL_ERROR
}
```

공개 예외는 최소 다음 정보를 제공한다.

```java
package org.ossproject.application.error;

import java.util.Objects;

public final class ApplicationException extends RuntimeException {
    private final ApplicationErrorCode code;
    private final String safeMessage;
    private final boolean retryable;

    public ApplicationException(ApplicationErrorCode code, String safeMessage,
                                boolean retryable, Throwable cause) {
        super(safeMessage, cause);
        this.code = Objects.requireNonNull(code, "code");
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("안전한 오류 메시지는 필수입니다.");
        }
        this.safeMessage = safeMessage;
        this.retryable = retryable;
    }

    public ApplicationErrorCode code() {
        return code;
    }

    public String safeMessage() {
        return safeMessage;
    }

    public boolean retryable() {
        return retryable;
    }
}
```

- A는 키움 오류를 위 코드로 변환한다.
- B는 분기에는 `code`, 화면과 TTS에는 `safeMessage`를 사용한다.
- 스택 트레이스와 키움 원문은 사용자에게 읽지 않는다.
- `retryable=true`여도 주문 제출을 자동 재시도하지 않는다.

## 12. 저장 Port와 DB 소유권

### 12.1 A가 구현할 저장 Port

다음 인터페이스는 `org.ossproject.application.port` 패키지에 둔다. 각 타입의 import는
가독성을 위해 생략한다.

```java
public interface OrderEventRepository {
    boolean append(OrderEvent event);
    List<OrderEvent> findByOrderId(String localOrderId);
}

public interface AccountSnapshotRepository {
    void save(AccountSnapshot snapshot);
    Optional<AccountSnapshot> findLatest(AccountRef account);
}

public interface SyncCheckpointRepository {
    Optional<SyncCheckpoint> find(AccountRef account, SyncResource resource);
    void save(SyncCheckpoint checkpoint);
}
```

`OrderRepository`, `OrderEventRepository`, `AccountSnapshotRepository`,
`SyncCheckpointRepository`까지가 장애 복구에 필요한 최소 집합이다. `CandleRepository`는
오프라인 차트·그래프 소리 재생을 만들 때 다음 단계로 추가한다.

```java
public interface CandleRepository {
    void upsert(SecurityId security, Candle candle);
    List<Candle> findRecent(SecurityId security, CandleInterval interval, int count);
}
```

동기화 모델은 다음으로 고정한다.

```java
package org.ossproject.finance.model;

public enum SyncResource {
    ORDERS,
    EXECUTIONS,
    BALANCE,
    POSITIONS
}
```

```java
package org.ossproject.finance.model;

import java.time.Instant;

public record SyncCheckpoint(
        AccountRef account,
        SyncResource resource,
        String lastSequence,
        Instant lastEventAt,
        Instant lastSuccessAt
) {
}
```

기존 `OrderRepository`는 유지하고 주문 스냅샷과 체결을 한 트랜잭션으로 저장한다.

### 12.2 B가 구현할 설정 저장 Port

`AccessibilityPreferencesRepository`는 `org.ossproject.accessibility.port`,
`SonificationPreferencesRepository`는 `org.ossproject.sonification.port`,
`WatchlistRepository`는 `org.ossproject.application.port`에 둔다. 인터페이스와 설정 모델은
B가 소유한다. SQLite 구현체를 `persistence-sqlite`에 둘 경우 A가 스키마·트랜잭션을 검토한다.

```java
public interface AccessibilityPreferencesRepository {
    AccessibilityPreferences load();
    void save(AccessibilityPreferences preferences);
}

public interface SonificationPreferencesRepository {
    SonificationPreferences load();
    void save(SonificationPreferences preferences);
}

```

`AccessibilityPreferencesRepository`와 `SonificationPreferencesRepository`는 현재 기능에
필수다. 관심종목 화면과 정책이 확정되기 전에는 아래 Port를 만들지 않는다.

```java
public interface WatchlistRepository {
    List<SecurityId> findAll();
    void replaceAll(List<SecurityId> securities);
}
```

접근성 설정 모델은 다음 필드로 고정한다. 실전 주문 재확인은 사용자가 끌 수 있는 설정이
아니므로 이 모델에 넣지 않는다.

```java
package org.ossproject.accessibility.notification;

public record AccessibilityPreferences(
        boolean speechEnabled,
        SpeechOptions speechOptions,
        boolean soundCuesEnabled,
        double fontScale,
        boolean highContrast,
        boolean screenReaderOptimized,
        boolean announceConnectionChanges
) {
}
```

```java
package org.ossproject.sonification.model;

import java.time.Duration;

public record SonificationPreferences(
        boolean enabled,
        GraphSonificationConfig graphConfig,
        double volume,
        Duration verbalAnchorInterval
) {
}
```

- 설정이 저장되어 있지 않으면 Repository는 `null` 대신 안전한 기본값을 반환한다.
- `fontScale` 기본값은 `1.0`, 권장 범위는 `1.0`부터 `2.0`이다.
- `volume` 범위는 `0.0`부터 `1.0`이다.
- `verbalAnchorInterval`은 0보다 커야 한다.

### 12.3 DB 규칙

목표 스키마는 다음과 같다. 기존 `orders`, `executions`, `anomaly_alerts`는 삭제하지 않고
마이그레이션으로 확장한다. `CORE`만 키움 모의주문 통합 전에 필수다.

| 단계 | 테이블 | 필수 컬럼 |
|---|---|---|
| CORE | `accounts` | `account_id`, `broker`, `masked_account_no`, `alias`, `environment`, `credential_alias`, `created_at`, `last_synced_at` |
| CORE | `orders` | 기존 컬럼 + `account_id`, `broker_order_id`, `client_request_id`, `exchange`, `original_order_id`, `remaining_quantity`, `submitted_at`, `accepted_at`, `last_synced_at` |
| CORE | `order_events` | `event_id`, `order_id`, `broker_order_id`, `event_type`, `status`, `filled_quantity`, `remaining_quantity`, `fill_price`, `reason_code`, `safe_reason`, `occurred_at`, `received_at` |
| CORE | `executions` | 기존 컬럼 + `account_id`, `broker_execution_id`, `broker_order_id`, `exchange`, `commission`, `tax`, `received_at` |
| CORE | `balance_snapshots` | `snapshot_id`, `account_id`, `cash`, `orderable_cash`, `locked_cash`, `total_asset`, `total_evaluation`, `total_profit_loss`, `snapshot_at` |
| CORE | `position_snapshots` | `snapshot_id`, `account_id`, `symbol`, `exchange`, `stock_name`, `quantity`, `available_quantity`, `average_price`, `current_price`, `evaluation_amount`, `profit_loss`, `profit_rate`, `snapshot_at` |
| CORE | `sync_checkpoints` | `account_id`, `resource_type`, `last_sequence`, `last_event_at`, `last_success_at` |
| CORE | `accessibility_settings` | `profile_id`, 음성·상태음·글자 크기·고대비·스크린리더·연결 안내 설정, `updated_at` |
| CORE | `sonification_settings` | `profile_id`, 활성화·음역·정규화·볼륨·음성 기준점 설정, `updated_at` |
| PHASE 2 | `candles` | `symbol`, `exchange`, `interval`, `open_time`, `open_price`, `high_price`, `low_price`, `close_price`, `volume`, `source`, `received_at` |
| PHASE 2 | `watchlists` | `watchlist_id`, `name`, `created_at` |
| PHASE 2 | `watchlist_items` | `watchlist_id`, `symbol`, `exchange`, `display_order`, `added_at` |
| OPTIONAL | `notification_history` | 사용자가 음성 알림 기록 기능을 요구할 때만 정의 |

해당 테이블을 도입할 때의 키와 인덱스는 다음과 같다.

```text
orders:               UNIQUE(account_id, broker_order_id), broker_order_id가 null이면 제외
order_events:         PRIMARY KEY(event_id), INDEX(order_id, occurred_at)
executions:           UNIQUE(account_id, broker_execution_id)
balance_snapshots:    INDEX(account_id, snapshot_at DESC)
position_snapshots:   INDEX(account_id, snapshot_at DESC)
candles:              PRIMARY KEY(symbol, exchange, interval, open_time)
sync_checkpoints:     PRIMARY KEY(account_id, resource_type)
watchlist_items:      PRIMARY KEY(watchlist_id, symbol, exchange)
notification_history: INDEX(deduplication_key, created_at DESC)
```

- 기존 마이그레이션 SQL은 수정하지 않고 새 버전을 뒤에 추가한다.
- 금액은 SQLite `TEXT`, 수량·시각은 `INTEGER`, ID·enum은 `TEXT`로 저장한다.
- 모든 외래키는 활성화하고 주문·체결·이벤트 저장은 트랜잭션으로 묶는다.
- `broker_execution_id`에는 고유 제약을 둬 중복 체결을 막는다.
- App Key, Secret, 토큰, 계좌 비밀번호는 SQLite에 저장하지 않는다.
- 비밀정보는 `windows-secret-store`에 저장하고 SQLite에는 `credential_alias`만 저장한다.
- 원본 JSON을 저장할 경우 계좌·토큰을 마스킹하고 보존 기간을 둔다.

### 12.4 만들지 않을 인터페이스

- 모든 기능을 한데 모은 `BrokerPort`: 재시도·연결·테스트 경계가 무너진다.
- JavaFX가 직접 구독하는 `BrokerEventBus`: 정합성 처리와 저장을 우회한다.
- 테이블마다 기계적으로 만든 Repository: aggregate 단위 Port만 둔다.
- 현재 단계의 `NotificationHistoryRepository`: 주문 이벤트 중복 제거는
  `OrderEventRepository`, 음성 큐 중복 제거는 기존 `SpeechQueue`가 담당한다.
- 별도 `ReconciliationPort`: `BrokerOrderPort`의 조회와 저장 Repository를 조합하는
  Application Service 책임이므로 중복 추상화다.

## 13. 현재 인터페이스 처리 방침

현재 코드와 목표 계약의 관계는 다음과 같다.

| 현재 타입 | 처리 방침 |
|---|---|
| `StockQueryPort` | UI 호환을 위해 유지하고 신규 코드는 `SecurityQueryPort` 사용 |
| `CandleQueryPort` | `String symbol`을 `SecurityId`로 변경 |
| `AccountPort` | 호환용으로 유지하고 신규 코드는 `AccountQueryPort` 사용 |
| `OrderPort` | 기존 모의주문 UI 호환용. 키움 신규 코드는 구현하지 않음 |
| `PortfolioPort` | 기존 화면 호환용. `AccountSnapshot`에서 변환하는 어댑터 제공 |
| `OrderLifecyclePort` | 로컬 모의엔진 호환용. 키움 전송은 `BrokerOrderPort`로 분리 |
| `OrderEventSource` | 기존 모의엔진 호환용. 키움은 `BrokerTradingStreamPort` 사용; UI 직접 구독 금지 |
| `MarketDataStreamPort` | `SecurityId`와 `EventSubscription` 기반으로 계약 변경 |
| `OrderRepository` | 유지하고 계좌·키움 주문번호 필드 확장 |
| `SpeechPort`, `SoundPort` | 현재 설계 유지. OS별 어댑터 선택은 기존 Factory 책임 |
| `SonificationPort` | 현재 설계 유지. 그래프 매핑 로직과 오디오 출력 경계가 이미 분리됨 |

기존 타입은 UI 마이그레이션이 끝나기 전까지 제거하지 않는다. 제거할 때는 별도 PR과
두 개발자의 승인이 필요하다.

### 13.1 현재 코드 재검증 결과

| 목표 계약 | 현재 상태 | 판정 |
|---|---|---|
| `SecurityQueryPort` | `StockQueryPort`만 존재 | 새 타입 필요 |
| `CandleQueryPort` | 존재하지만 `String symbol` 사용 | 서명 변경 필요 |
| `AccountQueryPort` | 단일 `AccountPort`만 존재 | 새 타입 필요 |
| `BrokerOrderPort` | `OrderLifecyclePort`가 도메인 `Order`까지 생성 | 증권사 응답 경계로 새로 분리 필요 |
| `MarketDataStreamPort` | 존재하지만 문자열 종목·add/remove Listener 방식 | 타입·수명 계약 변경 필요 |
| `BrokerTradingStreamPort` | 없음 | 새 타입 필요 |
| 금융 저장 Port 4개 | `OrderRepository`만 존재 | 3개 추가, 기존 1개 확장 필요 |
| B의 Application Port 3개 | 없음 | 새 서비스 경계 필요 |
| B의 Application Listener 2개 | 없음 | 새 타입 필요 |
| 설정 Repository 2개 | 없음 | 새 타입 필요 |
| 접근성·소니피케이션 출력 Port | 모두 존재 | 변경 불필요 |

따라서 이 문서의 MUST 인터페이스는 현재 코드를 이름만 바꾸기 위한 것이 아니다. 키움 원본
처리, 장애 복구, JavaFX 비동기 실행, 접근성 알림 사이에 현재 빠져 있는 경계를 채운다.

## 14. 이벤트·스레드·성능 규칙

- 공개 이벤트는 모두 불변 record로 만든다.
- 주문·체결·잔고 이벤트 콜백에서 네트워크나 디스크 작업을 블로킹하지 않는다.
- JavaFX Node 변경은 JavaFX Application Thread에서만 한다.
- WebSocket 수신 스레드에서 TTS 엔진을 직접 호출하지 않는다.
- 시세는 최신값 우선이며 중간 이벤트를 합칠 수 있다.
- 주문과 체결은 감사 데이터이므로 합치거나 누락하면 안 된다.
- 리스너 등록·해제와 발행은 thread-safe해야 한다.
- `close()`는 여러 번 호출해도 안전해야 한다.

## 15. 테스트 계약

A가 제공해야 하는 계약 테스트:

- OAuth 토큰 필드와 만료 시각 파싱
- REST `api-id`, 페이지네이션, 호출 제한
- WebSocket `LOGIN`, `REG`, `REMOVE`, `PING`
- 재연결 후 구독 복구
- 주문 POST 타임아웃 시 중복 주문 없음
- `00` 주문체결과 `04` 잔고 이벤트 중복 제거
- 앱 재시작 후 REST/SQLite reconciliation
- 모의와 실전 DB·자격증명 분리

B가 제공해야 하는 계약 테스트:

- 실전 주문은 미리보기와 명시적 확인 없이는 제출 불가
- 미리보기 만료·중복 제출 차단
- 주문·체결·연결 이벤트의 TTS 우선순위
- 동일 이벤트 음성 중복 제거
- 시세 폭주 시 UI 갱신 제한과 주문 이벤트 무손실
- 키보드만으로 주문 확인·취소 가능
- 스크린리더 이름·역할·상태 제공
- 음성 실패 시 화면 텍스트 유지

공통 테스트:

- 도메인 상태 전이 테스트
- 공개 Port를 기준으로 Fake와 Kiwoom 어댑터가 동일하게 동작하는 계약 테스트
- `BigDecimal` 직렬화 왕복 테스트
- 이벤트 시각과 순서 테스트

## 16. PR과 계약 변경 규칙

- 공개 record의 필드 추가도 생성자 호환성을 깨므로 계약 변경으로 본다.
- 공개 Port의 메서드 삭제·이름 변경·파라미터 변경은 A와 B 승인 후 진행한다.
- 구현을 먼저 병합하고 문서를 나중에 맞추지 않는다.
- 계약 변경 PR은 이 문서를 같은 PR에서 수정한다.
- 키움 TR 코드와 필드명 변경은 어댑터 내부 변경이면 B 승인 없이 가능하다.
- 사용자에게 보이는 주문 상태·음성 문구·확인 단계 변경은 공동 승인한다.
- DB 마이그레이션은 기존 버전을 고치지 않고 항상 다음 번호로 추가한다.

## 17. 구현 순서

1. `TradingEnvironment`, `Exchange`, `SecurityId`, `AccountRef` 추가
2. `OrderStatus`와 주문 전이 보강
3. `EventSubscription`과 A의 조회·주문 Outbound Port 추가
4. `BrokerTradingStreamPort`, 정규화 이벤트, `OrderEvent`, `AccountSnapshot` 추가
5. A의 CORE 저장 Port와 SQLite 마이그레이션 추가
6. B의 세 Application Port와 두 Application Listener 추가
7. Fake 어댑터와 Application Service 계약 테스트 작성
8. 키움 REST 조회와 시세 WebSocket 구현
9. 키움 모의 주문과 주문·잔고 WebSocket 구현
10. B의 JavaFX·접근성 모듈 연결
11. 장애 복구·접근성 통합 시나리오 테스트

## 18. 합의 체크리스트

아래 항목을 모두 확인한 뒤 계약 버전을 `1.0.0`으로 올린다.

- [ ] A/B 역할과 모듈 소유권에 동의한다.
- [ ] 키움 원본, SQLite 복구·캐시 원칙에 동의한다.
- [ ] `SecurityId(symbol, exchange)` 사용에 동의한다.
- [ ] 실행환경별 DB 분리와 실전 주문 보호에 동의한다.
- [ ] 주문 상태와 허용 전이에 동의한다.
- [ ] 일회용 `previewId` 기반 주문 확인에 동의한다.
- [ ] 주문 POST 자동 재시도 금지에 동의한다.
- [ ] 시세는 합칠 수 있지만 주문·체결 이벤트는 버리지 않는 데 동의한다.
- [ ] 오류 코드와 안전한 메시지 계약에 동의한다.
- [ ] 공개 Port 변경 시 공동 승인 규칙에 동의한다.

### 승인

```text
개발자 A: ____________________  날짜: __________
개발자 B: ____________________  날짜: __________
```
