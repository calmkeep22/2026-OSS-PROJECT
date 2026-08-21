# OpenStock Access

시각장애인과 저시력 사용자가 키보드·스크린리더·음성·청각 차트를 이용해 주식 정보를 탐색하고 모의주문을 연습할 수 있는 Java 17 오픈소스 데스크톱 앱입니다.

현재 기본 실행 모드는 모의투자입니다. 실제 키움 연동 코드는 별도 어댑터에 격리되어 있으며, 공식 API 명세와 운영 정책을 확정하기 전에는 실거래 주문을 전송하지 않습니다.

## 주요 기능

- JavaFX 기반 키보드 탐색, 큰 글자, 고대비, 스크린리더용 텍스트
- 우선순위·중복 병합·중단 정책이 있는 TTS 큐
- 가격 그래프의 추세와 변화를 연속 음높이로 표현하는 sonification
- 주문 미리보기와 명시적 재확인, 주문 한도·중복 주문 안전장치
- 잔고·주문·체결 생명주기를 처리하는 모의주문 엔진
- 가짜 시세/캔들/실시간 스트림과 키움 REST·WebSocket 어댑터
- SQLite 금융 데이터와 Windows DPAPI 비밀 저장소
- API 연결 화면에서 환경별 키움 자격증명 DPAPI 저장·재사용·삭제

## 모듈 구조

```text
ai-service
  AI 파트 (파이썬). 이상감지 · 차트 유사도 · 다음날 예측
  연동은 ai-service/INTEGRATION.md, 결과는 ai-service/results/index.html

apps/desktop-javafx
  JavaFX 화면과 DesktopServices 조립 루트

modules/finance-domain
  플랫폼 독립 금융 모델과 주문 상태 전이

modules/application
  Use Case, Port, 주문 안전 정책, 공통 adapter contract test

modules/mock-trading
  OrderLifecyclePort/AccountPort 기반 모의주문 엔진

modules/broker-api
  증권사 공통 REST 계약과 오류·재시도 모델

modules/kiwoom-adapter
  키움 REST/WebSocket 구현

modules/fake-adapters
  화면 개발용 시세·캔들·실시간 스트림 구현

modules/persistence-sqlite
  SQLite 영속화 구현

modules/secret-store-api
  비밀 저장소 공통 계약

modules/file-secret-store
  암호화 파일 저장 구현

modules/windows-secret-store
  Windows DPAPI 보호 구현

modules/accessibility
  TTS 큐, 음성/효과음 Port와 플랫폼 구현

modules/sonification
  프레임워크 독립 그래프 분석·매핑·재생·탐색과 출력 Port

modules/sonification-java-sound
  SonificationPort의 Java Sound PCM 출력 구현

modules/anomaly-detection
  규칙 기반 이상 탐지
```

의존성은 UI와 인프라에서 안쪽의 `application`/`finance-domain`으로만 향합니다. 데스크톱 화면은 구체 어댑터를 직접 생성하지 않고 `DesktopServices` 조립 루트에서 주입받습니다.

각 모듈의 공개 API, 의존성, 비목표는 해당 모듈의 `README.md`에 정리되어 있습니다.

### 비밀 저장 모듈 사용

비밀 저장 기능은 공통 계약, 파일 저장, Windows 보호 구현으로 분리되어 있습니다.

```text
secret-store-api ← file-secret-store ← windows-secret-store
        ↑                              ↑
   애플리케이션 코드              DesktopServices에서 선택
```

Windows 앱에서는 `SecretStoreFactory`로 DPAPI 저장소를 만든 뒤 `SecretStore` 타입으로
애플리케이션 코드에 주입합니다.

```java
Path secretDirectory = Path.of(
        System.getenv("LOCALAPPDATA"), "OpenStockAccess", "secrets");

try (SecretStore secrets = SecretStoreFactory.create(secretDirectory)) {
    char[] value = obtainSecretFromUser();
    try {
        secrets.store("kiwoom.mock.credentials", value);
    } finally {
        SecretBytes.wipe(value);
    }
}
```

모듈별 설치·호출 방법과 보안 계약은
[`secret-store-api`](modules/secret-store-api/README.md),
[`file-secret-store`](modules/file-secret-store/README.md),
[`windows-secret-store`](modules/windows-secret-store/README.md) 문서를 참고하세요.

## 실행

요구 사항: JDK 17. 별도의 전역 Gradle 설치는 필요하지 않습니다.

Windows PowerShell:

```powershell
./gradlew.bat :apps:desktop-javafx:run
```

전체 검증:

```powershell
./gradlew.bat clean test
```

Windows 휴대용 앱 이미지:

```powershell
./gradlew.bat :apps:desktop-javafx:packagePortable
```

## 안전 원칙

- 음성 명령만으로 주문을 즉시 제출하지 않습니다.
- 종목·매수/매도·수량·가격·예상 금액을 다시 읽고 명시적 확인을 받습니다.
- API 키와 토큰은 SQLite나 설정 파일에 평문으로 저장하지 않습니다.
- DPAPI를 사용할 수 없는 환경에서는 평문 저장으로 대체하지 않고 실패합니다.
- 색상이나 소리만으로 정보를 전달하지 않고 동등한 텍스트를 제공합니다.

현재 구현된 경계는 [모듈 아키텍처](docs/MODULE-ARCHITECTURE.md)를 기준으로 합니다. 팀 간 향후 계약은 [통합 계약](docs/A-B-INTEGRATION-CONTRACT.md), [인터페이스 명세](docs/A-B-INTERFACE-SPEC.md), [현재 구현 계획](docs/A-B-CURRENT-IMPLEMENTATION-PLAN.md)에서 관리합니다.

## 라이선스

[MIT](LICENSE)
