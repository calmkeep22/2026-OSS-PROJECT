package org.ossproject.kiwoom.http;

import org.ossproject.broker.BrokerException;
import org.ossproject.broker.BrokerTransientException;
import org.ossproject.broker.SensitiveDataMasker;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDK {@code HttpClient} 기반 전송.
 *
 * <p>사용자 PC에서 증권사로 직접 연결한다. 중앙 서버를 거치지 않으므로 API 키가 외부로
 * 나가지 않는다.
 */
public final class JdkHttpTransport implements HttpTransport, AutoCloseable {

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public JdkHttpTransport(HttpClient client) {
        if (client == null) {
            throw new IllegalArgumentException("HTTP 클라이언트는 필수입니다.");
        }
        this.client = client;
    }

    @Override
    public HttpTextResponse send(HttpTextRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);

        HttpRequest.BodyPublisher publisher = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8);
        builder.method(request.method(), publisher);

        try {
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpTextResponse(response.statusCode(), flatten(response), response.body());
        } catch (HttpTimeoutException e) {
            throw new BrokerTransientException("증권사 응답이 제한 시간 안에 오지 않았습니다.", e);
        } catch (IOException e) {
            throw new BrokerTransientException(
                    "증권사에 연결하지 못했습니다. " + SensitiveDataMasker.mask(String.valueOf(e.getMessage())), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerTransientException("요청이 중단되었습니다.", e);
        } catch (RuntimeException e) {
            throw new BrokerException(
                    "요청을 보내지 못했습니다. " + SensitiveDataMasker.mask(String.valueOf(e.getMessage())), e);
        }
    }

    /** 헤더는 값이 여러 개일 수 있어 첫 값만 취한다. */
    private static Map<String, String> flatten(HttpResponse<String> response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    @Override
    public void close() {
        // JDK 17 의 HttpClient 는 명시적으로 닫을 수 없다. GC 가 정리한다.
    }
}
