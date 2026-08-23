# voice-input-http

마이크로 받은 소리를 AI 서비스에 보내 글로 받아 옵니다. `VoiceInputPort` 의 HTTP
구현입니다.

## 공개 API

| 타입 | 하는 일 |
|---|---|
| `HttpVoiceInputAdapter` | 소리를 `/transcribe` 로 보내고 알아들은 말을 받습니다 |

```java
var voice = new HttpVoiceInputAdapter(
        URI.create("http://127.0.0.1:8765"), new MicrophoneCapture());

voice.useVocabulary(parser.vocabularyTerms());   // 종목명을 미리 알려 준다
Transcript heard = voice.listen(Duration.ofSeconds(8));
```

## 의존성

`voice-input-api` 와 Jackson 만 봅니다. JavaFX, Java Sound, `voice.javasound` 를
import 하지 않습니다 — 마이크 구현이 무엇인지 알지 못하고 포트 너머로만 받습니다.

`ai-insight-http` 의 배관을 가져다 쓰지 않습니다. 어댑터끼리 서로를 알면 하나를 갈아
끼울 때 다른 하나가 흔들립니다. 겹치는 코드가 조금 생기지만, 음성 인식을 다른 것으로
바꿀 때 AI 분석을 건드리지 않아도 되는 편이 낫습니다.

## 어휘를 반드시 먼저 등록합니다

이것은 선택이 아닙니다. 실측에서 어휘 없이 돌리면 이렇게 나왔습니다.

| 말한 것 | 어휘 없이 | 어휘 등록 후 |
|---|---|---|
| 에스케이하이닉스 현재가 | `SKINIX 현재가 알려줘` | `SK하이닉스, 현재가 알려줘` |
| 카카오 뉴스 읽어줘 | `다가오 뉴스 일거죠` | `카카오, 뉴스, 일거좋아` |

앞의 둘은 파서도 못 고칩니다. 고칠 만큼 기준을 낮추면 엉뚱한 종목에 붙고, 그 종목으로
주문이 나갑니다.

보유·관심 종목이 바뀌면 다시 등록합니다. `useVocabulary` 가 성공 여부를 돌려주는 이유는
실패가 조용하기 때문입니다 — 서버가 기동 중일 때 등록하면 실패하는데, 그것을 모르고
넘어가면 이후 계속 종목명을 놓칩니다.

## 못 쓰는 이유를 구분해 알립니다

마이크가 없는 것과 서버가 안 뜬 것은 사용자가 해야 할 일이 다릅니다. 뭉뚱그려 "음성
인식 실패" 라고 하면 무엇을 고쳐야 할지 알 수 없고, 화면을 볼 수 없으면 확인할 방법도
없습니다.

| 상황 | 알리는 말 |
|---|---|
| 마이크 없음 | 마이크를 찾지 못했습니다 |
| 소켓은 열렸는데 응답 없음 | 아직 준비 중입니다 |
| 연결 거부 | 서버가 떠 있지 않습니다 |
| 503 | 서버가 준 이유 그대로 (예: faster-whisper 미설치) |

서버가 준 이유를 우리 말로 덮지 않습니다. 실제로 그렇게 덮었다가 "faster-whisper 가
없다" 가 "서버가 응답하지 않는다" 로 바뀌어, 사용자가 엉뚱한 것을 고치려 들었습니다.

## HTTP/1.1 로 고정합니다

자바 `HttpClient` 기본값은 HTTP/2 라 h2c 업그레이드를 먼저 시도하는데, 서버(uvicorn)가
그것을 모릅니다. 로그에 `Unsupported upgrade request` 만 남고 요청은 시간 초과로
끝납니다. **서버는 200 을 돌려줬는데 앱에서는 "닿지 못했습니다" 가 되어** 양쪽 어디를
봐도 원인을 알기 어렵습니다.

## 확신도를 지어내지 않습니다

서버가 모른다고 하면 `Transcript.UNKNOWN_CONFIDENCE` 로 둡니다. 그럴듯한 값을 채워
넣으면 앱이 되물어야 할 때 되묻지 않습니다.

## 실제 서버에 붙여 보는 확인

평소 검사에서 빠져 있습니다. 창구를 고칠 때 손으로 돌립니다.

```powershell
cd ai-service; python server.py --port 8765
./gradlew.bat :modules:voice-input-http:test --tests "*LiveTranscribeProbe" `
    "-Dvoice.live=8765" "-Dvoice.wav=<WAV 가 든 폴더>"
```

## 비목표

- 소리를 만들지 않습니다. 마이크는 생성자로 받습니다.
- 말을 고치지 않습니다. 서버가 낸 그대로 담습니다 — 종목명을 붙이는 것은 파서의 일입니다.
- 재시도하지 않습니다. 사용자가 다시 누르는 편이 낫습니다.
