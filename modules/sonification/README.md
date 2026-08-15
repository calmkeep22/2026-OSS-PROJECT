# sonification

숫자 시계열을 연속적인 음높이 변화로 변환하고 분석·축약 재생·지점 탐색을 제공하는
프레임워크 독립 코어 모듈입니다. JavaFX, Java Sound, 금융 도메인, 데이터베이스 및
증권 API에 의존하지 않습니다.

실제 소리는 `SonificationPort` 구현체가 출력합니다. 기본 Java Sound 구현은 별도
`sonification-java-sound` 모듈에서 제공합니다.

## 모듈 관계

```text
desktop-javafx
  ├─ sonification                 모델·분석·매핑·재생 제어·출력 Port
  └─ sonification-java-sound      PCM 출력 어댑터
          └─ sonification
```

## 의존성 추가

분석과 프레임 매핑만 사용하는 모듈:

```kotlin
dependencies {
    implementation(project(":modules:sonification"))
}
```

Java Sound로 실제 재생하는 데스크톱 조립 모듈:

```kotlin
dependencies {
    implementation(project(":modules:sonification"))
    implementation(project(":modules:sonification-java-sound"))
}
```

현재 원격 Maven 저장소에는 배포하지 않습니다. Gradle `maven-publish` 설정으로 로컬 빌드
저장소에 소스·Javadoc과 함께 발행할 수 있습니다.

## 공개 API

| 타입 | 역할 |
|---|---|
| `TimeSeriesSample` | 스트림 키·값·시각을 가진 원본 표본 |
| `GraphValueScale` | 값을 -1~1 위치로 정규화하는 자동/기준점 음역 |
| `GraphAudioFrame` | 두 음높이 사이의 glide 출력 명령 |
| `GraphAnalyzer` | 추세·고점·저점·최대 지점 간 변화 요약 |
| `GraphPlaybackPlanner` | 축약 표본과 재생 시간 계획 생성 |
| `StreamingGraphSonifier` | 순서가 있는 표본을 연속 프레임으로 매핑 |
| `GraphPlaybackController` | 재생·일시정지·속도·원본 지점 탐색 관리 |
| `SonificationPort` | 실제 오디오 출력 구현과 코어 사이의 경계 |
| `SonificationOverflowPolicy` | 출력 큐 포화 시 어댑터의 처리 정책 |
| `SonificationPortContract` | 모든 출력 어댑터가 실행할 공통 계약 테스트 |

`LargestTriangleThreeBucketsReducer`, `EqualIntervalTimeMapping`,
`TimestampProportionalTimeMapping`은 재생 계획을 조정하는 기본 전략입니다.

## 기본 사용 방법

출력 구현은 앱의 조립 루트에서 만들고 코어에는 `SonificationPort` 타입으로 전달합니다.

```java
SonificationPort audio = createPlatformAudioOutput();
StreamingGraphSonifier graph = new StreamingGraphSonifier(audio);

try {
    graph.addListener(new GraphSonificationListener() {
        @Override public void onFrameMapped(GraphAudioFrame frame) {
            System.out.println(frame.currentValue() + " -> " + frame.toFrequencyHz() + "Hz");
        }

        @Override public void onFrameDropped(GraphAudioFrame frame) {
            // 소리 외의 텍스트 상태에도 누락 사실을 표시합니다.
        }

        @Override public void onPlaybackFailed(GraphAudioFrame frame, RuntimeException error) {
            // 동일한 값을 텍스트로 제공하고 출력 실패를 알립니다.
        }
    });

    graph.start("005930");
    graph.accept(new TimeSeriesSample("005930", 70_000, Instant.now()));
    graph.accept(new TimeSeriesSample("005930", 71_000, Instant.now().plusSeconds(1)));
} finally {
    graph.close(); // 빌린 포트를 정지하지만 닫지는 않습니다.
    audio.close(); // 포트를 만든 조립 루트가 마지막에 닫습니다.
}
```

Java Sound 어댑터를 사용할 때는 try-with-resources의 역순 종료를 이용할 수 있습니다.

```java
try (SonificationPort audio = new PcmGraphSonificationAdapter();
     StreamingGraphSonifier graph = new StreamingGraphSonifier(audio)) {
    graph.start("005930");
    graph.accept(sample);
}
```

## 출력 포트 소유권

- `StreamingGraphSonifier`는 주입받은 `SonificationPort`를 빌려 사용합니다.
- Sonifier의 `close()`는 리스너와 매핑 상태를 정리하고 출력은 정지하지만 포트를 닫지
  않습니다.
- 포트를 생성한 애플리케이션 조립 루트가 최종 `close()`를 호출합니다.
- `SonificationPort.stop()`은 전체 대기 큐를 비우므로 하나의 포트를 동시에 재생하는 여러
  Sonifier가 공유하면 안 됩니다.
- 하나의 재생 세션이 끝난 뒤 같은 포트를 다음 세션에 순차적으로 재사용하는 것은
  가능합니다.

## 출력 큐 계약

비동기 어댑터는 `overflowPolicy()`로 큐가 가득 찼을 때의 동작을 공개해야 합니다.

| 정책 | 의미 |
|---|---|
| `DROP_OLDEST` | 아직 시작하지 않은 가장 오래된 프레임을 버리고 최신 변화를 유지 |
| `REJECT_NEWEST` | 기존 대기 프레임을 유지하고 새 요청을 거부하거나 버림 |
| `BLOCK_PRODUCER` | 출력 공간이 생길 때까지 호출 스레드를 대기시킴 |

프레임을 버린 어댑터는 `SonificationOutputListener.onFrameDropped`를 호출해야 합니다.
`StreamingGraphSonifier`는 이를 `GraphSonificationListener.onFrameDropped`로 전달합니다.
앱은 누락이나 출력 실패를 소리만으로 알리지 말고 동등한 텍스트 상태도 제공해야 합니다.

## 매핑 원칙

- 첫 값을 기준음 440Hz로 재생합니다.
- 값이 올라가면 음높이가 올라가고 내려가면 음높이도 내려갑니다.
- 기본 고정 음역은 기준값 대비 ±5%를 220~880Hz로 표현합니다.
- 두 지점은 끊어진 알림음이 아니라 부드러운 로그 음높이 glide로 연결합니다.
- 범위를 벗어난 값은 최저·최고 음역으로 제한합니다.
- 절대 가격보다 정규화된 위치를 사용해 가격대가 다른 시계열에도 같은 음역을 씁니다.
- 투자 추천, 매매 신호 또는 자동매매 판단에는 사용하지 않습니다.

`GraphValueScale.automatic(...)`은 선택 구간의 최저·최고 값을 전체 음역에 배치해 모양을
강조합니다. `GraphValueScale.percentFromReference(...)`는 기준값 대비 고정 등락률을
사용해 서로 다른 그래프의 변동 크기를 비교할 수 있게 합니다.

## 과거 그래프 재생과 탐색

```java
List<TimeSeriesSample> history = loadHistory();
GraphValueScale scale = GraphValueScale.automatic(history);

GraphPlaybackController playback = new GraphPlaybackController(graph);
playback.load(history, scale, Duration.ofMillis(800));
playback.play();
playback.seek(3); // 네 번째 원본 지점 미리듣기
```

긴 그래프는 원본을 보존한 채 전체 듣기 지점만 제한합니다.

```java
GraphPlaybackPlanner planner = new GraphPlaybackPlanner(
        new LargestTriangleThreeBucketsReducer(),
        new TimestampProportionalTimeMapping(Duration.ofMillis(50)));
GraphPlaybackPlan plan = planner.plan(history, 48, Duration.ofSeconds(12));

playback.load(plan, GraphValueScale.automatic(history));
playback.play();
playback.seek(137); // 축약 여부와 관계없이 138번째 원본 지점 탐색
```

원본 표본은 `GraphPlaybackPlan.sourceSamples()`에 유지됩니다. 정확한 날짜·값의 TTS와 화면
표현은 애플리케이션 계층이 담당합니다.

## 새 출력 어댑터 구현

1. 플랫폼별 별도 Gradle 모듈에서 `SonificationPort`를 구현합니다.
2. `play`는 프레임의 audible duration 동안 호출자를 막지 않아야 합니다.
3. 볼륨은 0~1 범위를 검증하고 잘못된 값은 거부합니다.
4. 포화 정책을 명시하고 버린 프레임을 리스너로 알립니다.
5. `stop`은 재생과 대기 프레임을 정리하며 여러 번 호출해도 안전해야 합니다.
6. `close`는 멱등이어야 하며 이후의 새 작업을 거부해야 합니다.
7. 비동기 장치 오류를 `SonificationOutputListener`로 전달합니다.
8. 공통 `SonificationPortContract`를 실행합니다.

```kotlin
dependencies {
    api(project(":modules:sonification"))
    testImplementation(testFixtures(project(":modules:sonification")))
}
```

```java
final class MySonificationPortTest extends SonificationPortContract {
    @Override
    protected SonificationPort createPort() {
        return new MySonificationPort();
    }
}
```

## 테스트

```powershell
./gradlew.bat :modules:sonification:test
```

테스트는 정규화, 연속 glide 프레임, 스트림 순서, 재생 상태, 원본 지점 탐색, LTTB 극값
보존, 실제 시간 간격 매핑, 오류 전달 및 출력 포트 소유권을 검증합니다.
