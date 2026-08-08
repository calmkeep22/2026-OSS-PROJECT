# accessibility

시각장애인·저시력 사용자를 위한 음성 안내(TTS)와 상태음(sonification) 모듈입니다.
JavaFX, 데이터베이스, 금융 API에 의존하지 않으며 다른 데스크톱 UI나 백엔드에서도 재사용할 수 있습니다.

## 모듈이 담당하는 것

- 우선순위 기반 음성 큐와 현재 발화 중단
- 중복 유지, 대기 요청 교체, 전체 허용의 세 가지 병합 정책
- 대기열 최대 크기와 중요 주문 안내 보호
- 발화 시작, 완료, 중단, 실패 이벤트
- 속도, 음량, 음성 선택 옵션
- Windows, macOS, Linux TTS 어댑터 자동 선택
- 설치된 음성 목록 조회(지원 플랫폼)
- 의미가 구분되는 상태음과 음량·중단 제어

화면 구성, 주문 정책, 포트폴리오 표현처럼 애플리케이션에 종속되는 기능은 이 모듈 밖에서 처리합니다.

## 의존성 연결

```kotlin
// settings.gradle.kts
include(":modules:accessibility")

// 사용하는 모듈의 build.gradle.kts
dependencies {
    implementation(project(":modules:accessibility"))
}
```

Java 17 이상이 필요합니다.

## 빠른 시작

```java
import org.ossproject.accessibility.infrastructure.speech.SpeechAdapterFactory;
import org.ossproject.accessibility.notification.*;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;

SpeechPort port = SpeechAdapterFactory.create();
SpeechQueue queue = new SpeechQueue(
        port,
        SpeechOptions.DEFAULT,
        new SpeechQueueConfig(100));

queue.announce(new SpeechRequest(
        "삼성전자 현재가 7만 1천 원",
        SpeechPriority.INFORMATION,
        "quote-005930",
        SpeechMergePolicy.REPLACE_PENDING));

// 애플리케이션 종료 시 한 번 호출
queue.close();
```

`announce()`는 요청을 받으면 `true`, 중복 요청이거나 대기열에 자리가 없으면 `false`를 반환합니다.

## 우선순위

```text
CRITICAL > ORDER > CONNECTION > ALERT > USER_REQUEST > INFORMATION
```

새 요청의 우선순위가 현재 발화보다 높으면 현재 발화를 중단하고 새 요청을 먼저 읽습니다.
대기열이 가득 차면 수신 요청보다 중요하지 않은 낮은 우선순위 요청부터 제거합니다.
`ORDER`와 `CRITICAL` 대기 요청은 용량 확보를 위해 자동 제거하지 않습니다. 대기열이 보호 요청으로 가득 차면 새 요청은 거부될 수 있습니다.

## 병합 정책

| 정책 | 동작 | 권장 용도 |
|---|---|---|
| `KEEP_FIRST` | 같은 키가 발화 중이거나 대기 중이면 새 요청 거부 | 주문 결과, 연결 끊김 등 한 번만 읽어야 하는 안내 |
| `REPLACE_PENDING` | 발화 중인 요청은 유지하고 같은 키의 대기 요청을 최신 값으로 교체 | 실시간 시세, 잔고, 진행률 |
| `ALLOW_ALL` | 같은 키도 모두 대기열에 추가 | 사용자가 명시적으로 요청한 반복 읽기 |

3개 인자를 받는 `SpeechRequest(text, priority, key)` 생성자는 기본값으로 `KEEP_FIRST`를 사용합니다.
키가 비어 있으면 안내 문장 자체가 중복 제거 키가 됩니다.

## 음성 옵션

```java
SpeechOptions options = SpeechOptions.DEFAULT
        .withRate(1.2)                            // 0.5 ~ 2.0
        .withVolume(80)                           // 0 ~ 100
        .withVoiceName("Microsoft Heami Desktop"); // null이면 시스템 기본 음성

queue.setOptions(options);
```

`SpeechOptions`는 불변 record이므로 `with*` 메서드는 새 객체를 반환합니다. macOS의 `say`는 발화별 음량 옵션을 제공하지 않아 `volume`을 적용하지 않습니다.

설치된 음성을 제공하는 어댑터에서는 선택 UI를 만들 수 있습니다.

```java
if (port instanceof SpeechVoiceProvider provider) {
    List<SpeechVoice> voices = provider.availableVoices();
}
```

현재 Windows와 macOS가 음성 목록 조회를 지원합니다. 조회 실패 시 빈 목록을 반환하므로 시스템 기본 음성을 계속 사용할 수 있습니다.

## 이벤트와 오류 처리

```java
queue.addListener(new SpeechListener() {
    @Override public void onStarted(SpeechRequest request) {}
    @Override public void onCompleted(SpeechRequest request) {}
    @Override public void onInterrupted(SpeechRequest request) {}
    @Override public void onFailed(SpeechRequest request, RuntimeException error) {
        // 화면 상태 영역에 오류를 표시하거나 오류 상태음을 재생
    }
});
```

리스너 하나가 예외를 던져도 큐와 다른 리스너에는 영향을 주지 않습니다. 리스너는 음성 작업 스레드에서 호출될 수 있으므로 JavaFX 컨트롤 변경은 `Platform.runLater(...)`로 UI 스레드에 전달해야 합니다.

Windows, macOS, Linux 어댑터에서 프로세스 시작이나 합성에 실패하면 `SpeechSynthesisException`이 `onFailed`로 전달됩니다. 지원하지 않는 OS에서는 `SilentSpeechAdapter`를 사용하며 의도적으로 소리를 내지 않습니다.

## 상태음

```java
try (SoundPort sound = new ToneSoundAdapter()) {
    sound.setVolume(0.7); // 0.0 ~ 1.0
    sound.play(SoundCue.ORDER_FILLED);
    sound.play(SoundCue.CONNECTION_LOST);
    sound.stop();
}
```

지원 큐는 다음과 같습니다.

- `SUCCESS`, `WARNING`, `ERROR`
- `ANOMALY_HIGH`
- `CONNECTION_LOST`, `CONNECTION_RESTORED`
- `ORDER_FILLED`, `ORDER_REJECTED`

같은 상태음이 아직 재생 대기 중이면 중복 추가하지 않습니다. 내부 대기열도 제한되어 있어 이벤트 폭주가 메모리를 계속 소비하지 않습니다.

## 플랫폼 구현

| OS | 구현 | 실행 방식 | 속도 | 음량 | 음성 선택 |
|---|---|---|---|---|---|
| Windows | `WindowsSpeechAdapter` | PowerShell + `System.Speech` | O | O | O |
| macOS | `MacSpeechAdapter` | `say` | O | 발화별 적용 불가 | O |
| Linux | `LinuxSpeechAdapter` | `spd-say` | O | O | 이름 전달 |
| 기타 | `SilentSpeechAdapter` | 무음 | - | - | - |

Windows 구현은 문장과 PowerShell 스크립트를 Base64로 인코딩해 한글, 따옴표, 줄바꿈 때문에 명령이 깨지지 않도록 합니다. Linux에서는 `speech-dispatcher`의 `spd-say`가 설치되어 있어야 합니다.

## 사용자 정의 TTS 연결

클라우드 TTS나 별도 엔진은 `SpeechPort`를 구현해 큐에 주입합니다.

```java
final class CustomSpeechPort implements SpeechPort {
    @Override public void speak(String text) throws InterruptedException {
        // 외부 TTS 호출. stop()이 호출되면 가능한 한 빨리 반환해야 합니다.
    }

    @Override public void stop() {}

    @Override public void applyOptions(SpeechOptions options) {}
}

SpeechQueue queue = new SpeechQueue(new CustomSpeechPort());
```

`SpeechPort`와 `SoundPort`는 `AutoCloseable`이므로 try-with-resources를 사용할 수 있습니다. `close()` 이후 큐에 요청이나 설정을 추가하면 `IllegalStateException`이 발생하며, `close()` 자체는 여러 번 호출해도 안전합니다.

## 테스트

프로젝트 루트에서 실행합니다.

```powershell
.\gradlew.bat --no-daemon :modules:accessibility:test
```

테스트는 우선순위 중단, 병합 정책, 제한된 대기열, clear/close 수명주기, 리스너 오류 격리, 플랫폼 선택, 명령 인코딩과 상태음 설정을 검증합니다. OS가 실제로 음성을 재생하는지는 해당 OS에서 별도의 수동 청취 테스트가 필요합니다.

## 패키지 구조

```text
org.ossproject.accessibility
├─ port             SpeechPort, SpeechVoiceProvider, SoundPort
├─ notification     SpeechQueue, SpeechRequest, SpeechPriority,
│                   SpeechMergePolicy, SpeechQueueConfig, SpeechOptions,
│                   SpeechVoice, SpeechListener, SoundCue
└─ infrastructure
   ├─ speech         SpeechAdapterFactory, Windows/Mac/Linux/SilentSpeechAdapter
   └─ sound          ToneSoundAdapter
```
