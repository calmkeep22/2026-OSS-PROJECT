package org.ossproject.voice.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.ossproject.voice.AudioCapturePort;
import org.ossproject.voice.RecognitionMode;
import org.ossproject.voice.Transcript;
import org.ossproject.voice.VoiceInputPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 마이크로 받은 소리를 AI 서비스에 보내 글로 받아 온다.
 *
 * <p>{@code ai-insight-http} 의 배관을 가져다 쓰지 않는다. 어댑터끼리 서로를 알면
 * 하나를 갈아 끼울 때 다른 하나가 흔들린다. 겹치는 코드가 조금 생기지만, 음성 인식을
 * 다른 것으로 바꿀 때 AI 분석을 건드리지 않아도 되는 편이 낫다.
 *
 * <p>못 쓰는 이유를 두 군데서 받는다. 마이크가 없는 것과 서버가 안 뜬 것은 사용자가
 * 해야 할 일이 다르다. 뭉뚱그려 "음성 인식 실패" 라고 하면 무엇을 고쳐야 할지 알 수
 * 없고, 화면을 볼 수 없으면 확인할 방법도 없다.
 */
public final class HttpVoiceInputAdapter implements VoiceInputPort {

    /**
     * 인식을 기다리는 시간.
     *
     * <p>첫 호출은 모델을 올리느라 오래 걸린다. 실측에서 적재 3.5초에 인식 6초까지
     * 나왔다. 짧게 잡으면 처음 한 번이 늘 실패하고, 사용자는 기능이 고장 난 줄 안다.
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 상태를 묻고 기다리는 시간.
     *
     * <p>넉넉히 잡는다. 서버는 뜨자마자 답하지 못한다. 기동할 때 모델과 지수를 읽는
     * warm 이 실측 12.5초 걸리고, 그동안 소켓은 열려 있지만 응답이 없다. 짧게 잡으면
     * 앱을 켜자마자 마이크를 누른 사용자가 늘 실패한다.
     *
     * <p>배경 스레드에서 기다리므로 화면이 멈추지는 않는다. 서버가 아예 없으면
     * 연결이 즉시 거부되어 여기까지 기다리지도 않는다.
     */
    private static final Duration STATUS_TIMEOUT = Duration.ofSeconds(20);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final AudioCapturePort microphone;
    private final HttpClient http;
    private final Duration statusTimeout;
    private volatile String serverReason = "";

    public HttpVoiceInputAdapter(URI base, AudioCapturePort microphone) {
        this(base, microphone, HttpClient.newBuilder()
                // HTTP/1.1 로 고정한다. 자바 기본값은 HTTP/2 라 먼저 h2c 업그레이드를
                // 시도하는데, 서버(uvicorn)는 그것을 모른다. 로그에 "Unsupported
                // upgrade request" 만 남고 요청은 시간 초과로 끝난다. 서버는 200 을
                // 돌려줬는데 앱에서는 "서버에 닿지 못했습니다" 가 되어, 무엇이 잘못됐는지
                // 양쪽 어디를 봐도 알기 어렵다.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build());
    }

    HttpVoiceInputAdapter(URI base, AudioCapturePort microphone, HttpClient http) {
        this(base, microphone, http, STATUS_TIMEOUT);
    }

    HttpVoiceInputAdapter(URI base, AudioCapturePort microphone, HttpClient http,
                          Duration statusTimeout) {
        this.base = base;
        this.microphone = microphone;
        this.http = http;
        this.statusTimeout = statusTimeout;
    }

    @Override
    public Transcript listen(Duration limit) {
        byte[] wav = microphone.recordUtterance(limit);
        if (wav.length == 0) return Transcript.nothing(mode());

        HttpRequest request = HttpRequest.newBuilder(base.resolve("/transcribe"))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(wav))
                .build();
        JsonNode answer = send(request);
        if (answer == null) return Transcript.nothing(mode());

        String text = answer.path("말").asText("");
        JsonNode confidence = answer.get("확신도");
        // 확신도를 서버가 모른다고 하면 우리도 모르는 채로 둔다. 그럴듯한 값을
        // 채워 넣으면 되물어야 할 때 되묻지 않는다.
        double score = confidence == null || confidence.isNull()
                ? Transcript.UNKNOWN_CONFIDENCE
                : confidence.asDouble();
        return new Transcript(text, score, RecognitionMode.FREE_SPEECH);
    }

    /**
     * 알아들을 말을 서버에 미리 등록한다.
     *
     * <p>실측에서 이것을 하지 않으면 "에스케이하이닉스" 가 "SKINIX" 로, "카카오" 가
     * "다가오" 로 나왔다. 파서도 못 고치는 수준이라 선택이 아니다.
     */
    @Override
    public boolean useVocabulary(List<String> phrases) {
        ObjectNode body = JSON.createObjectNode();
        body.set("phrases", JSON.valueToTree(phrases));
        try {
            return send(HttpRequest.newBuilder(base.resolve("/transcribe/vocabulary"))
                    .timeout(CALL_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                    .build()) != null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("어휘를 JSON 으로 만들지 못했습니다.", impossible);
        }
    }

    @Override
    public boolean available() {
        return unavailableReason().isEmpty();
    }

    @Override
    public String unavailableReason() {
        String microphoneReason = microphone.unavailableReason();
        if (!microphoneReason.isEmpty()) return microphoneReason;
        refreshServerStatus();
        return serverReason;
    }

    @Override
    public RecognitionMode mode() {
        return available() ? RecognitionMode.FREE_SPEECH : RecognitionMode.UNAVAILABLE;
    }

    private void refreshServerStatus() {
        // 지난번 이유를 먼저 지운다. 남겨 두면 이미 고쳐진 문제를 계속 읽어 준다.
        serverReason = "";
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/transcribe/status"))
                .timeout(statusTimeout).GET().build();
        JsonNode status = send(request);
        if (status == null) {
            // send() 가 서버에서 받은 이유를 남겼으면 그것을 쓴다. 우리 말로 덮으면
            // "faster-whisper 가 없다" 가 "서버가 응답하지 않는다" 로 바뀌어,
            // 사용자는 무엇을 고쳐야 할지 알 수 없게 된다.
            if (serverReason.isBlank()) {
                serverReason = "음성 인식 서버가 응답하지 않습니다. 앱을 다시 시작해주세요.";
            }
            return;
        }
        if (!status.path("쓸수있음").asBoolean(false)) {
            String given = status.path("사유").asText("");
            serverReason = given.isBlank() ? "음성 인식을 준비하지 못했습니다." : given;
            return;
        }
        serverReason = "";
    }

    /** @return 서버가 준 JSON. 실패하면 null 이고 이유가 {@code serverReason} 에 남는다. */
    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 503) {
                serverReason = detail(response.body(), "음성 인식을 쓸 수 없습니다.");
                return null;
            }
            if (response.statusCode() >= 400) {
                serverReason = detail(response.body(),
                        "음성 인식이 실패했습니다 (" + response.statusCode() + ").");
                return null;
            }
            return JSON.readTree(response.body());
        } catch (java.net.http.HttpConnectTimeoutException notThere) {
            serverReason = "음성 인식 서버를 띄우지 못했습니다."
                    + " ai-service 에서 pip install -r requirements.txt 를 실행해주세요.";
            return null;
        } catch (java.net.http.HttpTimeoutException stillStarting) {
            // 소켓은 열렸는데 답이 없다. 서버가 기동 중이라는 뜻이다. "닿지 못했다" 고
            // 하면 사용자는 서버가 없는 줄 알고 설치를 다시 하려 든다. 실제로 그랬다.
            serverReason = "음성 인식 서버가 아직 준비 중입니다. 잠시 뒤 다시 눌러주세요.";
            return null;
        } catch (java.net.ConnectException refused) {
            serverReason = "음성 인식 서버가 떠 있지 않습니다. 앱을 다시 시작해주세요.";
            return null;
        } catch (java.io.IOException unreachable) {
            serverReason = "음성 인식 서버에 닿지 못했습니다: " + unreachable.getMessage();
            return null;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            serverReason = "음성 인식을 기다리다 중단되었습니다.";
            return null;
        }
    }

    /** 서버가 준 이유를 그대로 쓴다. 우리가 다시 지어내면 실제 원인이 가려진다. */
    private static String detail(byte[] body, String fallback) {
        try {
            String given = JSON.readTree(body).path("detail").asText("");
            return given.isBlank() ? fallback : given;
        } catch (java.io.IOException unreadable) {
            return fallback;
        }
    }

    @Override
    public void close() {
        microphone.close();
    }
}
