package org.ossproject.broker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그에 남기기 전에 민감한 값을 가린다.
 *
 * <p>API 키, 시크릿, 액세스 토큰, 계좌번호가 로그 파일이나 예외 메시지에 남으면 그 자체로
 * 사고다. 증권사 응답 본문을 그대로 로그에 찍는 실수를 막기 위해 HTTP 계층은 반드시
 * 이 클래스를 거쳐야 한다.
 */
public final class SensitiveDataMasker {

    /** {@code "appkey":"..."} 같은 JSON 필드. 필드명은 대소문자를 가리지 않는다. */
    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "(\"(?:appkey|appsecret|app_key|app_secret|secretkey|secret_key|token|access_token"
                    + "|approval_key|password|passwd)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);

    /** {@code Authorization: Bearer xxx} 헤더. */
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(Bearer\\s+)([A-Za-z0-9._\\-]+)", Pattern.CASE_INSENSITIVE);

    /** 8자리 이상 연속 숫자. 계좌번호로 간주한다. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\b(\\d{8,})\\b");

    private SensitiveDataMasker() {
    }

    /**
     * 문자열 안의 민감한 값을 가린다.
     *
     * <p>JSON 시크릿 필드, Bearer 토큰, 8자리 이상 숫자를 모두 처리한다.
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = replaceGroup(JSON_SECRET_FIELD, text, 2);
        masked = replaceGroup(BEARER_TOKEN, masked, 2);
        masked = maskLongDigits(masked);
        return masked;
    }

    /** 토큰이나 키 하나를 가린다. 앞 4자만 남긴다. */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "****";
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****";
    }

    /** 계좌번호를 뒤 4자리만 남기고 가린다. */
    public static String maskAccountNo(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            return "****";
        }
        String digits = accountNo.replaceAll("[^0-9]", "");
        if (digits.length() <= 4) {
            return "****";
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    private static String maskLongDigits(String text) {
        Matcher matcher = LONG_DIGITS.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group(1);
            String replacement = "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String replaceGroup(Pattern pattern, String text, int secretGroup) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            StringBuilder replacement = new StringBuilder();
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String value = matcher.group(group);
                replacement.append(group == secretGroup ? "****" : (value == null ? "" : value));
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
