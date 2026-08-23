package org.ossproject.voice.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.ossproject.voice.AudioCapturePort;
import org.ossproject.voice.RecognitionMode;
import org.ossproject.voice.Transcript;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서버와 주고받는 부분.
 *
 * <p>가짜 HttpClient 를 만들지 않고 진짜 HTTP 서버를 띄운다. 이 창구에서 났던 실제
 * 문제들 — 한글이 깨져 본문이 안 읽히는 것, 503 을 500 처럼 다루는 것 — 은 모두
 * 진짜로 주고받아야 드러난다.
 */
class HttpVoiceInputAdapterTest {

    private HttpServer server;
    private URI base;
    private final Map<String, String> received = new ConcurrentHashMap<>();

    /** 마이크 대신. 정해 둔 소리를 돌려준다. */
    private static final class FakeMicrophone implements AudioCapturePort {
        private final byte[] audio;
        private String reason = "";
        boolean closed;

        FakeMicrophone(byte[] audio) {
            this.audio = audio;
        }

        @Override public byte[] recordUtterance(Duration limit) { return audio; }
        @Override public boolean available() { return reason.isEmpty(); }
        @Override public String unavailableReason() { return reason; }
        @Override public void close() { closed = true; }
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /**
     * 창구 하나를 연다.
     *
     * <p>기록은 <b>실제로 들어온 경로</b>로 남긴다. {@code HttpServer} 는 창구를
     * 접두사로 맞추므로, {@code /transcribe} 창구가 {@code /transcribe/status} 까지
     * 받는다. 등록한 경로로 기록하면 상태 조회를 인식 요청으로 착각한다.
     */
    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            String actual = exchange.getRequestURI().getPath();
            received.put(actual, new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            received.put(actual + ":type", String.valueOf(
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private HttpVoiceInputAdapter adapter(AudioCapturePort microphone) {
        return new HttpVoiceInputAdapter(base, microphone);
    }

    @Nested
    @DisplayName("한마디 듣기")
    class Listening {

        @Test void 서버가_준_말을_그대로_담는다() {
            respond("/transcribe", 200,
                    "{\"말\": \"삼성전자, 매수, 열주\", \"확신도\": 0.818}");

            Transcript heard = adapter(new FakeMicrophone(new byte[]{1, 2, 3}))
                    .listen(Duration.ofSeconds(5));

            assertEquals("삼성전자, 매수, 열주", heard.text(), "한글이 깨지면 안 된다");
            assertEquals(0.818, heard.confidence(), 0.0001);
            assertEquals(RecognitionMode.FREE_SPEECH, heard.mode());
        }

        @Test void 소리를_WAV_로_보낸다() {
            respond("/transcribe", 200, "{\"말\": \"\", \"확신도\": null}");

            adapter(new FakeMicrophone(new byte[]{1, 2, 3})).listen(Duration.ofSeconds(5));

            assertEquals("audio/wav", received.get("/transcribe:type"));
        }

        /**
         * 아무 말도 안 했으면 서버를 부르지 않는다. 빈 소리를 보내면 모델이 헛돌고,
         * whisper 는 무음에 "감사합니다" 같은 군말을 뱉는 버릇이 있다.
         */
        @Test void 녹음된_소리가_없으면_서버를_부르지_않는다() {
            respond("/transcribe", 500, "{\"detail\": \"불렀으면 안 된다\"}");

            Transcript heard = adapter(new FakeMicrophone(new byte[0]))
                    .listen(Duration.ofSeconds(5));

            assertFalse(heard.heardSomething());
            assertFalse(received.containsKey("/transcribe"), "서버를 불렀다");
        }

        /** 확신도를 서버가 모른다고 하면 우리도 모르는 채로 둔다. */
        @Test void 확신도가_null_이면_모름으로_둔다() {
            respond("/transcribe", 200, "{\"말\": \"관심종목\", \"확신도\": null}");

            Transcript heard = adapter(new FakeMicrophone(new byte[]{1}))
                    .listen(Duration.ofSeconds(5));

            assertFalse(heard.confidenceKnown(), "모르는 것을 아는 것처럼 만들면 안 된다");
        }
    }

    @Nested
    @DisplayName("못 쓸 때 이유를 구분해 알린다")
    class Unavailability {

        @Test void 마이크가_없으면_마이크_이야기를_한다() {
            FakeMicrophone microphone = new FakeMicrophone(new byte[0]);
            microphone.reason = "이 컴퓨터에서 마이크를 찾지 못했습니다.";
            respond("/transcribe/status", 200, "{\"쓸수있음\": true}");

            HttpVoiceInputAdapter voice = adapter(microphone);

            assertFalse(voice.available());
            assertTrue(voice.unavailableReason().contains("마이크"));
            assertEquals(RecognitionMode.UNAVAILABLE, voice.mode());
        }

        @Test void 서버가_못_쓴다고_하면_그_이유를_그대로_전한다() {
            respond("/transcribe/status", 200,
                    "{\"쓸수있음\": false, \"사유\": \"faster-whisper 가 설치되지 않았습니다\"}");

            String reason = adapter(new FakeMicrophone(new byte[0])).unavailableReason();

            assertTrue(reason.contains("faster-whisper"),
                    "우리가 지어낸 말로 덮으면 진짜 원인이 가려진다: " + reason);
        }

        @Test void 서버가_안_떠_있으면_닿지_못했다고_한다() {
            // 아무 창구도 열지 않는다.
            String reason = adapter(new FakeMicrophone(new byte[0])).unavailableReason();

            assertFalse(reason.isBlank(), "조용히 실패하면 사용자는 이유를 알 수 없다");
        }

        /**
         * 소켓은 열렸는데 답이 없으면 기동 중이다. 서버가 없는 것과 다르다.
         *
         * <p>실제로 이것을 구분하지 못해 사용자가 "음성 인식 서버에 닿지 못했습니다" 를
         * 보고 설치가 잘못된 줄 알았다. 서버는 멀쩡히 떠 있었고 warm 이 12.5초 걸리던
         * 중이었다. 고칠 것이 없는데 고치려 들게 만드는 안내는 없느니만 못하다.
         */
        @Test void 기동_중이면_준비_중이라고_한다() throws Exception {
            server.createContext("/transcribe/status", exchange -> {
                try {
                    Thread.sleep(3_000);   // 아직 warm 중인 서버
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });

            HttpVoiceInputAdapter voice = new HttpVoiceInputAdapter(
                    base, new FakeMicrophone(new byte[0]),
                    java.net.http.HttpClient.newBuilder()
                            .version(java.net.http.HttpClient.Version.HTTP_1_1).build(),
                    Duration.ofMillis(300));

            String reason = voice.unavailableReason();

            assertTrue(reason.contains("준비 중"), "실제로 받은 말: " + reason);
            assertFalse(reason.contains("닿지 못했"),
                    "서버가 있는데 없다고 하면 사용자가 엉뚱한 것을 고치려 든다");
        }

        @Test void 인식_중_503_은_사유를_담아_빈_말로_돌아온다() {
            respond("/transcribe", 503,
                    "{\"detail\": \"faster-whisper 가 설치되지 않았습니다\"}");

            HttpVoiceInputAdapter voice = adapter(new FakeMicrophone(new byte[]{1}));
            Transcript heard = voice.listen(Duration.ofSeconds(5));

            assertFalse(heard.heardSomething());
            assertTrue(voice.unavailableReason().contains("faster-whisper"));
        }
    }

    @Nested
    @DisplayName("어휘 등록")
    class Vocabulary {

        @Test void 한글_종목명이_깨지지_않고_간다() {
            respond("/transcribe/vocabulary", 200, "{\"받은어휘\": 2}");

            adapter(new FakeMicrophone(new byte[0]))
                    .useVocabulary(List.of("삼성전자", "SK하이닉스"));

            String sent = received.get("/transcribe/vocabulary");
            assertTrue(sent.contains("삼성전자"), "보낸 것: " + sent);
            assertTrue(sent.contains("SK하이닉스"));
        }
    }

    @Test
    @DisplayName("닫으면 마이크도 닫는다")
    void closingReleasesTheMicrophone() {
        FakeMicrophone microphone = new FakeMicrophone(new byte[0]);

        adapter(microphone).close();

        assertTrue(microphone.closed, "마이크를 붙들고 있으면 다른 프로그램이 못 쓴다");
    }
}
