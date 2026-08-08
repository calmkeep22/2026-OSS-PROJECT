# sonification

숫자 시계열의 그래프 모양을 연속적인 음높이 변화로 변환하고, 요약·축약 재생·지점 탐색을 제공하는 청각 그래프 모듈입니다.
JavaFX, 금융 도메인, 데이터베이스와 증권 API에 의존하지 않습니다.

## 모듈 경계

- `model`: 시계열 표본, 음역, 오디오 프레임과 요약 결과를 담는 불변 값 객체
- `GraphAnalyzer`: 전체 추세·고점·저점·가장 큰 지점 간 변화 계산
- `GraphPlaybackPlanner`: 긴 원본 시계열을 듣기 좋은 재생 계획으로 변환
- `StreamingGraphSonifier`: 값과 음역을 연속적인 pitch glide 프레임으로 매핑
- `GraphPlaybackController`: 재생·일시정지·속도·원본 지점 탐색 상태 관리
- `port.SonificationPort`: Java Sound 같은 플랫폼별 출력 구현과 핵심 로직 사이의 경계
- `infrastructure.sound.PcmGraphSonificationAdapter`: 16kHz PCM 사인파를 재생하는 기본 Java Sound 구현

애플리케이션은 종목 API 데이터를 `TimeSeriesSample`로 바꾸고, 정확한 가격 TTS·화면 상태·TTS 중 음량 감소를 조정합니다. 따라서 금융 API나 JavaFX가 바뀌어도 이 모듈의 그래프 매핑 로직은 바뀌지 않습니다.

## 매핑 원칙

- 첫 번째 값을 기준음 440Hz로 재생합니다.
- 기준값보다 높아지면 음높이가 올라가고, 낮아지면 음높이도 내려갑니다.
- 기본 설정은 기준값 대비 ±5%를 220~880Hz 범위로 표현합니다.
- 두 가격 지점은 끊어진 알림음이 아니라 부드러운 pitch glide로 연결합니다.
- 범위를 벗어난 값은 최저·최고 음역으로 제한해 불쾌한 고주파나 저주파를 방지합니다.
- 절대 주가가 아니라 기준가 대비 변화율을 사용하므로 가격대가 다른 종목에도 같은 음역을 사용합니다.
- 사용하는 앱은 동일한 값을 화면 텍스트로도 제공해야 합니다.
- 투자 추천, 매수·매도 신호 또는 자동매매 판단에 사용하지 않습니다.

## 탐색 방식

- `GraphAnalyzer`는 전체 추세, 시작·종료 값, 최고·최저점과 가장 큰 지점 간 변화를 계산합니다.
- `GraphValueScale.automatic(...)`은 선택 기간의 최저·최고 값을 전체 음역에 배치해 그래프 모양을 선명하게 표현합니다.
- `GraphValueScale.percentFromReference(...)`는 기준값 대비 고정 등락률을 사용해 서로 다른 차트의 변동 크기를 비교할 수 있게 합니다.
- `GraphPlaybackController`는 전체 재생, 일시정지, 다시 재생, 속도 변경과 특정 지점의 짧은 음높이 미리듣기를 제공합니다.
- `LargestTriangleThreeBucketsReducer`는 원본의 첫·끝과 중요한 굴곡을 보존하면서 긴 차트의 전체 듣기 지점 수를 제한합니다.
- `TimestampProportionalTimeMapping`은 장중 공백이나 날짜 간격이 큰 구간을 더 길게 들려주되 모든 지점에 최소 재생 시간을 보장합니다.
- 원본 표본은 `GraphPlaybackPlan.sourceSamples()`에 그대로 남으므로 축약 재생 후에도 정확한 모든 지점을 탐색할 수 있습니다.
- 정확한 날짜와 값의 TTS 표현은 애플리케이션 계층이 담당합니다.

## 사용 예시

```java
SonificationPort audio = new PcmGraphSonificationAdapter();
StreamingGraphSonifier graph = new StreamingGraphSonifier(audio);

graph.addListener(new GraphSonificationListener() {
    @Override public void onFrameMapped(GraphAudioFrame frame) {
        System.out.println(frame.currentValue() + " -> " + frame.toFrequencyHz() + "Hz");
    }
});

graph.start("005930");
graph.accept(new TimeSeriesSample("005930", 70_000, Instant.now()));
graph.accept(new TimeSeriesSample("005930", 71_000, Instant.now().plusSeconds(1)));

graph.close();
```

작은 과거 차트는 모든 지점에 동일한 재생 간격을 지정할 수 있습니다.

```java
List<TimeSeriesSample> history = loadHistory();
GraphValueScale scale = GraphValueScale.automatic(history);

GraphPlaybackController playback = new GraphPlaybackController(graph);
playback.load(history, scale, Duration.ofMillis(800));
playback.play();
playback.seek(3); // 네 번째 지점의 음높이를 짧게 미리듣기
```

긴 차트는 원본을 보존한 채 전체 듣기만 제한합니다.

```java
GraphPlaybackPlanner planner = new GraphPlaybackPlanner(
        new LargestTriangleThreeBucketsReducer(),
        new TimestampProportionalTimeMapping(Duration.ofMillis(50)));
GraphPlaybackPlan plan = planner.plan(history, 48, Duration.ofSeconds(12));

playback.load(plan, GraphValueScale.automatic(history));
playback.play();
playback.seek(137); // 축약 여부와 관계없이 138번째 원본 지점 탐색
```

오디오 장치 오류는 프레임을 큐에 넣은 뒤 재생 스레드에서 발생할 수도 있습니다. 기본 PCM 어댑터는 이 오류를 `SonificationOutputListener`로 알리고, `StreamingGraphSonifier`가 `GraphSonificationListener.onPlaybackFailed(...)`까지 전달합니다. 앱은 소리가 나지 않을 때 동일한 값과 실패 상태를 화면 텍스트로 제공해야 합니다.

## 테스트

```powershell
.\gradlew.bat :modules:sonification:test
```

테스트는 음높이 정규화, 연속 glide, 스트림 순서, 재생 상태, 원본 지점 탐색, LTTB 극값 보존, 실제 시간 간격 매핑, 비동기 오디오 오류 전달과 자원 종료를 검증합니다.

실제 시세 연결은 애플리케이션 계층에서 `TimeSeriesSample`로 변환해 전달합니다. 현재 데스크톱 데모는 Fake 과거 종가의 축약 재생과 1초 간격 실시간 모니터링을 모두 제공하며, 추후 WebSocket 구현으로 바꿔도 이 모듈은 변경하지 않습니다.
