# 2026 OSS Project

시각장애인과 저시력 사용자를 고려한 접근 가능한 금융 데스크톱 애플리케이션입니다.

## 현재 구현 범위

개발자 B가 A의 금융 구현을 기다리지 않고 개발할 수 있도록 실제 구현과 Fake 구현을 Port로 분리했습니다.

- JavaFX 17 실행 골격
- Gradle 멀티모듈과 단방향 의존성
- Application Port와 Use Case
- 독립된 모의주문 및 Fake 시세 어댑터
- 키보드로 탐색 가능한 포트폴리오 표
- 큰 글자 및 고대비 전환
- 주문 입력, 미리보기, 명시적인 재확인, 모의주문 접수
- 규칙 기반 가격·거래량 이상 감지
- 우선순위·최신값 교체·중복 허용·중단 정책과 최대 용량이 적용된 TTS 큐
- Windows·macOS·Linux TTS 어댑터, 음성 목록 조회와 비지원 환경의 무음 대체 구현
- 주문·연결·이상 감지·성공·경고·오류를 구분하는 Sonification 상태음
- 주문 금액과 포트폴리오 계산 단위 테스트

## 모듈 구조

```text
apps/desktop-javafx
  JavaFX 화면, 키보드 탐색, 내비게이션, 구현체 조립

modules/finance-domain
  종목, 가격, 계좌, 주문 도메인 모델

modules/application
  Port 인터페이스와 Use Case

modules/mock-trading
  인메모리 모의주문, 주문 검증, 모의 계좌

modules/anomaly-detection
  가격 급등락과 거래량 급증 규칙

modules/accessibility
  플랫폼 독립 TTS Port, 제한된 우선순위 큐, OS별 어댑터, 상태음

modules/fake-adapters
  UI 독립 개발용 Fake 종목·일봉 데이터
```

의존성은 다음 방향만 허용합니다.

```text
desktop-javafx ─┬─> application ──> finance-domain
                ├─> mock-trading ─> application
                ├─> fake-adapters -> application
                ├─> anomaly-detection -> finance-domain
                └─> accessibility
```

`finance-domain`과 `application`은 JavaFX를 참조하지 않습니다. 개발자 A의 구현이 준비되면
`PortfolioPort`, `OrderPort`, `StockQueryPort` 구현체를 추가하고 JavaFX 부트스트랩에서 교체합니다.

## 요구 사항

- JDK 17
- Windows, macOS 또는 Linux

Gradle은 별도 설치할 필요가 없습니다.

## 실행

Windows PowerShell:

```powershell
.\gradlew.bat :apps:desktop-javafx:run
```

테스트:

```powershell
.\gradlew.bat test
```

## 키보드 단축키

- `Alt+D`: 대시보드
- `Alt+A`: 계좌와 모의주문
- `Alt+S`: 접근성 설정
- `Tab` / `Shift+Tab`: 컨트롤 간 이동
- 방향키: 표 행 또는 선택 항목 탐색
- `Enter`: 기본 버튼 실행
- `Escape`: 주문 재확인 취소

음성 안내와 상태음은 설정 화면에서 각각 켜고 끌 수 있습니다.
TTS나 오디오 장치를 사용할 수 없어도 동일한 정보는 항상 화면의 텍스트로 제공됩니다.

## 안전 원칙

- 음성이나 단일 입력만으로 주문을 즉시 제출하지 않습니다.
- 주문 제출 전에 종목, 매수·매도, 수량, 가격과 예상 금액을 다시 보여줍니다.
- 상승·하락 및 이익·손실 상태를 색상만으로 표현하지 않습니다.
- API 키, 토큰과 계좌 정보는 저장소에 커밋하지 않습니다.

## 라이선스

[MIT](./LICENSE)
