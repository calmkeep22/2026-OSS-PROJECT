package org.ossproject.kiwoom;

import org.ossproject.broker.BrokerAuthException;
import org.ossproject.broker.BrokerException;
import org.ossproject.broker.BrokerRateLimitException;
import org.ossproject.broker.BrokerTransientException;
import org.ossproject.broker.SensitiveDataMasker;
import org.ossproject.kiwoom.http.HttpTextResponse;

import java.time.Duration;

/**
 * HTTP 응답을 도메인 예외로 옮긴다.
 *
 * <p>상위 계층이 {@link BrokerException#isRetryable()} 만 보고 재시도 여부를 판단할 수 있도록,
 * 여기서 상태 코드의 의미를 한 번에 해석한다. 응답 본문은 그대로 노출하지 않고
 * {@link SensitiveDataMasker} 를 거친다.
 */
public final class KiwoomErrorMapper {

    /** 오류 메시지에 실을 응답 본문 길이 상한. 로그가 폭발하지 않도록 자른다. */
    private static final int MAX_BODY_LENGTH = 300;

    private KiwoomErrorMapper() {
    }

    /**
     * 실패 응답을 예외로 바꾼다.
     *
     * @param operation 사용자에게 보여 줄 작업 이름
     */
    public static BrokerException toException(String operation, HttpTextResponse response) {
        int status = response.statusCode();
        String detail = summarize(response.body());

        if (status == 401 || status == 403) {
            return new BrokerAuthException(
                    operation + " 인증에 실패했습니다. API 키와 접근 권한을 확인해 주세요. " + detail);
        }
        if (status == 429) {
            return new BrokerRateLimitException(
                    operation + " 요청이 증권사 호출 한도를 넘었습니다. " + detail, parseRetryAfter(response));
        }
        if (status == 408 || status >= 500) {
            return new BrokerTransientException(
                    operation + " 중 증권사 서버 오류가 발생했습니다. 상태 코드 " + status + ". " + detail);
        }
        return new BrokerException(
                operation + " 요청이 거부되었습니다. 상태 코드 " + status + ". " + detail);
    }

    /** {@code Retry-After} 헤더는 초 단위 숫자로 온다고 가정한다. */
    private static Duration parseRetryAfter(HttpTextResponse response) {
        return response.header("Retry-After")
                .map(String::trim)
                .filter(value -> value.matches("\\d+"))
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(null);
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String masked = SensitiveDataMasker.mask(body.strip());
        if (masked.length() > MAX_BODY_LENGTH) {
            masked = masked.substring(0, MAX_BODY_LENGTH) + "...";
        }
        return "응답 " + masked;
    }
}
