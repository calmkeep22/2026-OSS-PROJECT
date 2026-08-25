# OpenStock Access

시각장애인과 저시력 사용자가 키보드·스크린리더·음성·청각 차트를 이용해 주식 정보를 탐색하고 모의주문을 연습할 수 있는 Java 17 오픈소스 데스크톱 앱입니다.

현재 기본 실행 모드는 모의투자입니다. 실제 키움 연동 코드는 별도 어댑터에 격리되어 있으며, 공식 API 명세와 운영 정책을 확정하기 전에는 실거래 주문을 전송하지 않습니다.

## 주요 기능

**접근성**

- JavaFX 기반 키보드 탐색, 큰 글자, 고대비, 스크린리더용 텍스트
- 우선순위·중복 병합·중단 정책이 있는 TTS 큐
- 가격 그래프의 추세와 변화를 연속 음높이로 표현하는 sonification
- 모든 그래프에 같은 값을 담은 표를 함께 제공

**시세와 주문**

- 키움 REST·WebSocket 어댑터와 화면 개발용 가짜 어댑터
- 호가창, 체결 목록, 캔들 차트와 보조지표
- 주문 미리보기와 명시적 재확인, 주문 한도·중복 주문 안전장치
- 잔고·주문·체결 생명주기를 처리하는 모의주문 엔진

**음성 명령** — 마이크 단추 또는 Alt+V

- 화면 이동·현재가·뉴스·잔고 조회, 관심종목 담기·빼기, 읽기 속도·큰 글씨·고대비
- 읽던 안내 멈추기와 다시 듣기 — 소리로만 받는 사용자는 놓친 안내를 되돌려 볼 수 없습니다
- 인식은 앱이 띄우는 파이썬 서비스 안에서 돕니다. 소리를 바깥으로 보내지 않습니다

> 주문은 말로 확정되지 않습니다. "삼성전자 매수 열 주" 는 주문 화면을 채우기만 하고,
> 실제 발주는 기존 재확인 창을 거칩니다. 잘못 알아들은 한 번이 그대로 체결로 나가면
> 안 되고, 화면을 볼 수 없는 사용자는 그것을 눈으로 확인할 수도 없습니다.

**AI 분석** — 파이썬 분석 서비스를 앱이 직접 띄워 붙입니다

- 다음 거래일 변동성 예측과 이상 움직임 감지, 종목 위험도
- 닮은 차트 종목과 그 구간 다음에 실제로 있었던 일
- 뉴스 기사·사건 요약·감성 지수
- 우리가 계산한 값만 근거로 답하는 질의응답(챗봇). 사고파는 판단과 미래 가격은 답하지 않습니다

> 분석 결과에는 신뢰도와 단서가 값에서부터 따라붙습니다. 검증에서 우연과 구별되지
> 않은 예측은 그 사실을 함께 알립니다. 값을 받지 못하면 빈 칸으로 두지 않고 받지
> 못했다고 적습니다 — 화면을 볼 수 없는 사용자는 지어낸 값과 실제를 구별할 수 없습니다.

**보안**

- SQLite 금융 데이터와 Windows DPAPI 비밀 저장소
- API 연결 화면에서 환경별 키움 자격증명 DPAPI 저장·재사용·삭제

## 모듈 구조

```text
ai-service
  AI 파트 (파이썬). 이상감지 · 차트 유사도 · 다음날 예측 · 뉴스
  server.py 가 라이브러리를 HTTP 로 감싸고, 앱이 자식 프로세스로 띄움
  연동은 ai-service/INTEGRATION.md, 결과는 ai-service/results/index.html

apps/desktop-javafx
  JavaFX 화면과 DesktopServices 조립 루트

modules/finance-domain
  플랫폼 독립 금융 모델과 주문 상태 전이
  market · order · account · orderbook 으로 나뉘고, 공유 식별 타입만 루트에 있음

modules/application
  Use Case, Port, 주문 안전 정책, 공통 adapter contract test

modules/mock-trading
  OrderLifecyclePort/AccountPort 기반 모의주문 엔진

modules/broker-api
  증권사 공통 REST 계약과 오류·재시도 모델 (auth · error · resilience)

modules/voice-input-api
  음성 명령 계약. 무엇을 시킬 수 있는지와 알아들은 말을 명령으로
  바꾸는 규칙. 인식기를 갈아 끼워도 이 규칙은 그대로다

modules/voice-input-java-sound
  마이크에서 한마디를 받아 WAV 로 만든다. 말이 끝난 것을 알아채고 끊는다

modules/voice-input-http
  ai-service 의 /transcribe 를 부르는 어댑터

modules/kiwoom-adapter
  키움 REST/WebSocket 구현 (query · mapping · config · http · stream)

modules/fake-adapters
  화면 개발용 시세·캔들·실시간 스트림 구현

modules/ai-insight-api
  AI 분석 계약. 예측·이상감지·닮은 차트·뉴스·질의응답과
  함께 전해야 하는 단서를 값에서 강제

modules/ai-insight-http
  ai-service 를 HTTP 로 부르는 어댑터

modules/persistence-sqlite
  주문·체결·이상 감지 이력 저장과 재시작 후 과거 주문 조회

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

### AI 분석 사용

AI 기능은 **켜 두면 앱이 알아서 띄웁니다.** 배포본에는 Python 런타임·AI 의존성·저장
모델·Whisper base 모델이 함께 들어 있고, 앱을 닫을 때 AI 서버도 함께 내립니다.
배포본 사용자는 Python이나 pip를 설치하거나 터미널을 열 필요가 없습니다.

아래 준비는 저장소에서 직접 개발 실행할 때만 필요합니다.

```powershell
cd ai-service
pip install -r requirements.txt
```

파이썬이 없거나 패키지가 빠져 있으면 **앱은 그대로 돌아가고 화면에 그 사실을 적습니다.**
시세와 주문은 AI 없이도 동작합니다.

서버는 `127.0.0.1` 에만 엽니다. 인증이 없고 같은 기계의 앱만 부르므로 바깥에 열 이유가
없습니다. 포트를 바꿔야 하면 `OPENSTOCK_AI_PORT` 를 씁니다.

> 뉴스는 구글 뉴스 RSS 를 씁니다. API 키가 필요 없는 대신 **최근 7일까지만** 줍니다.
> 그래서 앱이 하루 한 번 보유·관심 종목 뉴스를 받아 `ai-service/data/` 에 쌓습니다.
> 오늘 안 받으면 그날치는 영영 없습니다.

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

사용자에게 전달할 포터블 ZIP:

```powershell
./gradlew.bat :apps:desktop-javafx:packagePortableZip
```

결과 파일은
`apps/desktop-javafx/build/package/release/OpenStockAccess-0.1.0-windows-x64-portable.zip`
입니다. 사용자는 압축을 풀고 `OpenStockAccess.exe`만 실행하면 됩니다.

위 배포 작업은 Java 런타임뿐 아니라 Python AI 서버와 모델도 함께 묶습니다. 빌드하는
컴퓨터에는 JDK 17과 Python 3.12가 필요하지만, 생성된 앱을 사용하는 사람은 Java나
Python을 따로 설치할 필요가 없습니다. 첫 빌드는 AI 의존성을 받기 때문에 인터넷 연결과
충분한 디스크 공간이 필요합니다.

앱을 처음 실행하면 `%LOCALAPPDATA%\OpenStockAccess\openstock.db`가 자동으로
생성됩니다. 성공한 주문 응답과 이상 감지 이력을 여기에 저장하며, API 키·App Secret·
Access Token은 SQLite에 저장하지 않습니다. 키움 서버가 주문 상태의 원본이고 SQLite는
과거 이력 확인용이므로, 연결이 끊겼을 때 오래된 미체결 상태로 주문 가능 여부를 판단하지 않습니다.

Windows 설치 프로그램(EXE, WiX Toolset 필요):

```powershell
./gradlew.bat :apps:desktop-javafx:packageWindowsInstaller
```

## 음성 명령 사용

상단 **음성 명령** 단추 또는 **Alt+V** 로 엽니다. 신호음이 난 뒤에 말하면 됩니다.
늘 듣고 있지 않습니다 — 마이크를 계속 열어 두면 언제 녹음되는지 알 수 없습니다.

```text
관심종목 보여줘      계좌        청각차트 열어줘     이상감지
뒤로               그만        다시 말해줘        도움말
천천히 / 빠르게      큰글씨       고대비            예수금 알려줘
삼성전자 현재가      카카오 뉴스   삼성전자 관심종목에 담아줘
삼성전자 매수 열 주   (주문 화면을 채우기만 하고, 확인 단추를 눌러야 나갑니다)
```

말이 인식기에 정확히 들어가지 않아도 됩니다. 아는 종목과 명령어에 가장 가까운 것을
골라 붙입니다. 다만 매수·매도·종목명은 붙이는 기준을 빡빡하게 잡았습니다 — "매수" 와
"매도" 는 한 글자 차이라, 헐겁게 붙이면 팔라는 말이 사라는 말이 됩니다.

인식은 `ai-service` 안에서 돕니다. **AI 분석과 같은 서버**라 따로 준비할 것이 없습니다.
공식 배포본은 Whisper base 모델까지 포함하므로 최초 음성 사용 때도 모델을 내려받지
않습니다. 저장소에서 직접 실행하는 개발 환경은 로컬 캐시가 없으면 한 번 내려받습니다.

### 마이크가 안 잡힐 때

**설정 → 음성 설정 → 마이크** 에서 장치를 고르면 그 선택을 기억합니다. 자바가 고르는
기본 장치가 운영체제의 기본을 따라가지 않아, 소리가 하나도 들어오지 않는 장치가
잡히는 경우가 있습니다.

어느 장치가 실제로 소리를 받는지는 이 도구로 눈으로 볼 수 있습니다.

```powershell
java tools\MicMeter.java
```

무엇으로 알아들었는지는 로그에 남습니다.

```powershell
Get-Content -Tail 20 "$env:LOCALAPPDATA\OpenStockAccess\ai-service.log"
```

## 안전 원칙

- 음성 명령만으로 주문을 즉시 제출하지 않습니다.
- 종목·매수/매도·수량·가격·예상 금액을 다시 읽고 명시적 확인을 받습니다.
- API 키와 토큰은 SQLite나 설정 파일에 평문으로 저장하지 않습니다.
- DPAPI를 사용할 수 없는 환경에서는 평문 저장으로 대체하지 않고 실패합니다.
- 색상이나 소리만으로 정보를 전달하지 않고 동등한 텍스트를 제공합니다.
- 값을 받지 못하면 빈 칸으로 두지 않고 받지 못했다고 적습니다. 빈 칸은 "이상 없음" 으로 읽힙니다.
- AI 분석은 신뢰도와 단서를 값에서 강제합니다. 화면이 그중 무엇을 보여 줄지 고를 수 없습니다.
- 질의응답은 계산한 값만 근거로 답합니다. 사고파는 판단과 미래 가격은 답하지 않습니다.

현재 구현된 경계는 [모듈 아키텍처](docs/MODULE-ARCHITECTURE.md)를 기준으로 합니다. 팀 간 향후 계약은 [통합 계약](docs/A-B-INTEGRATION-CONTRACT.md), [인터페이스 명세](docs/A-B-INTERFACE-SPEC.md), [현재 구현 계획](docs/A-B-CURRENT-IMPLEMENTATION-PLAN.md)에서 관리합니다.

## 라이선스

[MIT](LICENSE)
