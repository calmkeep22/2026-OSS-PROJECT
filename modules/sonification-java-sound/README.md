# sonification-java-sound

`sonification` 코어의 `GraphAudioFrame`을 Java Sound 기반 16비트 mono PCM으로 재생하는
출력 어댑터입니다. 분석·정규화·축약·재생 상태는 포함하지 않으며 오디오 장치 출력만
담당합니다.

## 요구 사항과 의존성

- Java 17 이상
- `javax.sound.sampled.SourceDataLine`을 제공하는 실행 환경
- `modules:sonification`

```kotlin
dependencies {
    implementation(project(":modules:sonification"))
    implementation(project(":modules:sonification-java-sound"))
}
```

Java Sound는 JDK API이므로 별도 네이티브 라이브러리 의존성은 없습니다. 오디오 장치가
없거나 사용할 수 없는 환경에서는 비동기 실패 이벤트를 전달합니다.

## 공개 API

- `org.ossproject.sonification.javasound.PcmGraphSonificationAdapter`

그 외 출력 라인 생성과 PCM 렌더링 보조 타입은 공개 API가 아닙니다.

## 사용 예제

```java
import org.ossproject.sonification.StreamingGraphSonifier;
import org.ossproject.sonification.javasound.PcmGraphSonificationAdapter;
import org.ossproject.sonification.model.TimeSeriesSample;
import org.ossproject.sonification.port.SonificationPort;

try (SonificationPort audio = new PcmGraphSonificationAdapter();
     StreamingGraphSonifier graph = new StreamingGraphSonifier(audio)) {
    audio.setVolume(0.7);
    graph.start("005930");
    graph.accept(new TimeSeriesSample("005930", 70_000, Instant.now()));
    graph.accept(new TimeSeriesSample("005930", 71_000, Instant.now().plusSeconds(1)));
}
```

리소스는 선언 역순으로 닫히므로 Sonifier가 먼저 멈추고 출력 어댑터가 마지막에 오디오
라인과 작업 스레드를 종료합니다.

## 출력 형식

- 표본화율: 16,000Hz
- 샘플: signed 16-bit
- 채널: mono
- 바이트 순서: little-endian
- 파형: sine
- 음높이 보간: logarithmic glide
- 기본 볼륨: 0.65

## 큐 포화 정책

어댑터는 단일 출력 스레드와 최대 2개의 대기 프레임을 사용합니다. 큐가 가득 차면 아직
재생을 시작하지 않은 가장 오래된 프레임 하나를 제거하고 최신 프레임을 보존합니다.

```java
audio.overflowPolicy(); // SonificationOverflowPolicy.DROP_OLDEST
```

제거된 프레임은 `SonificationOutputListener.onFrameDropped`로 알립니다. 이를 통해 앱은
화면에 “오디오 처리 지연으로 이전 지점이 생략됨” 같은 동등한 텍스트 상태를 제공할 수
있습니다.

`stop()`은 현재 출력을 중단하고 모든 대기 프레임을 폐기합니다. 따라서 동일한 어댑터를
여러 Sonifier가 동시에 공유하면 안 됩니다.

## 오류와 종료

- 장치 열기·쓰기 실패는 `onPlaybackFailed`로 전달됩니다.
- 장치 오류를 비밀리에 무시하거나 성공 상태로 표시하지 않습니다.
- `close()`는 여러 번 호출해도 안전합니다.
- 닫힌 뒤 `play`, `setVolume`, 새 리스너 등록은 `IllegalStateException`으로 거부합니다.
- `stop`과 리스너 제거는 닫힌 뒤에도 안전하게 호출할 수 있습니다.

## 데스크톱 앱 연결

구체 어댑터는 화면이 아니라
`apps/desktop-javafx/.../composition/DesktopServices.java`에서 생성합니다. 화면과 차트
제어기는 `SonificationPort`에만 의존합니다. 앱 종료 순서는 다음과 같습니다.

```text
AccessibleChartController.close()
  → StreamingGraphSonifier.close()
  → SonificationPort.close()
```

## 테스트

```powershell
./gradlew.bat :modules:sonification-java-sound:test
```

테스트는 공통 `SonificationPortContract`, 로그 음높이 보간, 비동기 장치 실패, 실제 큐
포화 시 가장 오래된 대기 프레임 제거와 알림을 검증합니다.
