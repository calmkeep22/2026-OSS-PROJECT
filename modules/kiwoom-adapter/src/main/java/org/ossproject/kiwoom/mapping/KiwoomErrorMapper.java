package org.ossproject.kiwoom.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.ossproject.broker.error.BrokerAuthException;
import org.ossproject.broker.error.BrokerException;
import org.ossproject.broker.error.BrokerRateLimitException;
import org.ossproject.broker.error.BrokerTransientException;
import org.ossproject.broker.auth.SensitiveDataMasker;
import org.ossproject.kiwoom.http.HttpTextResponse;

import java.time.Duration;
import java.util.Set;

/**
 * 키움 응답을 도메인 예외로 옮긴다.
 *
 * <p>키움은 업무 오류를 HTTP 상태가 아니라 본문의 {@code return_code} 로 알린다. 200 응답이라도
 * {@code return_code} 가 0이 아니면 실패다. 이걸 놓치면 빈 결과를 정상으로 착각하게 되므로,
 * 모든 조회는 {@link #requireSuccessBody} 를 거친다.
 *
 * <p>상위 계층이 {@link BrokerException#isRetryable()} 만 보고 재시도 여부를 판단할 수 있도록
 * 여기서 의미를 해석한다. 응답 본문은 {@link SensitiveDataMasker} 를 거쳐 노출한다.
 */
public final class KiwoomErrorMapper {

    /** 오류 메시지에 실을 응답 본문 길이 상한. 로그가 폭발하지 않도록 자른다. */
    private static final int MAX_BODY_LENGTH = 300;

    /** 재발급이나 자격증명 확인이 필요한 코드. */
    private static final Set<String> AUTH_CODES = Set.of(
            "1514", "1515", "1516",
            "8001", "8002", "8003", "8005", "8006", "8009", "8010", "8011", "8012",
            "8020", "8030", "8031", "8040", "8050", "8103");

    /** 호출량 제한. 잠시 뒤 재시도할 수 있다. */
    private static final Set<String> RATE_LIMIT_CODES = Set.of("1687", "1700", "1701", "1702");

    /** 일시적 서버 오류. */
    private static final Set<String> TRANSIENT_CODES = Set.of("1999");

    private KiwoomErrorMapper() {
    }

    /**
     * 실패한 HTTP 응답을 예외로 바꾼다.
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

    /**
     * 공식 문서 오류코드가 담기는 위치.
     *
     * <p>{@code return_code} 는 문서의 오류코드가 아니라 큰 분류다. 문서 표의 코드는
     * {@code return_msg} 안 대괄호에 들어온다. 실제 응답에서 두 가지 형태를 확인했다.
     *
     * <pre>
     *   허용된 요청 개수를 초과하였습니다[1700:허용된 API 요청 개수를 초과하였습니다...]
     *   [2000](RC4058:모의투자 장종료)
     * </pre>
     */
    private static final java.util.regex.Pattern DOCUMENTED_CODE =
            java.util.regex.Pattern.compile("\\[(\\d{3,4})[:\\]]");

    /**
     * 본문의 오류 코드를 확인하고 실패면 예외를 던진다.
     *
     * <p>HTTP 200 이라도 업무 오류일 수 있으므로 모든 응답이 이 검사를 거쳐야 한다. 성공 여부는
     * {@code return_code == 0} 으로 판단하고, 어떤 오류인지는 {@code return_msg} 안의 문서
     * 코드로 판단한다.
     *
     * @param operation 사용자에게 보여 줄 작업 이름
     * @param root      파싱된 응답 본문
     * @param response  헤더 확인용 원본 응답
     */
    public static void requireSuccessBody(String operation, JsonNode root, HttpTextResponse response) {
        JsonNode codeNode = root == null ? null : root.get("return_code");
        if (codeNode == null || codeNode.isNull()) {
            // 일부 TR은 return_code 를 주지 않는다. 그 경우 HTTP 상태로만 판단한다.
            return;
        }
        String returnCode = codeNode.asText().trim();
        if ("0".equals(returnCode)) {
            return;
        }

        JsonNode messageNode = root.get("return_msg");
        String rawMessage = messageNode == null || messageNode.isNull() ? "" : messageNode.asText().trim();
        String message = SensitiveDataMasker.mask(rawMessage);
        String documented = documentedCodeOf(rawMessage);

        String shown = documented == null ? returnCode : documented;
        String detail = operation + " 실패. 코드 " + shown + (message.isBlank() ? "" : ". " + message);

        if (documented != null) {
            if (AUTH_CODES.contains(documented)) {
                throw new BrokerAuthException(detail);
            }
            if (RATE_LIMIT_CODES.contains(documented)) {
                throw new BrokerRateLimitException(detail, parseRetryAfter(response));
            }
            if (TRANSIENT_CODES.contains(documented)) {
                throw new BrokerTransientException(detail);
            }
        }
        throw new BrokerException(detail);
    }

    /** {@code return_msg} 안에서 문서 오류코드를 뽑는다. 없으면 {@code null}. */
    static String documentedCodeOf(String returnMessage) {
        if (returnMessage == null || returnMessage.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = DOCUMENTED_CODE.matcher(returnMessage);
        return matcher.find() ? matcher.group(1) : null;
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
