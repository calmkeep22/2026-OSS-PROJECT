package org.ossproject.ai;

/**
 * AI 분석을 받지 못했다.
 *
 * <p>사유를 사용자에게 그대로 보여 줄 수 있는 문장으로 담는다. 분석이 없는 것과 분석
 * 결과가 "이상 없음" 인 것은 전혀 다른 뜻이라, 받지 못한 것을 조용히 넘기면 안 된다.
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
