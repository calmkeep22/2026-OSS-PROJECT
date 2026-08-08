package org.ossproject.secret;

/**
 * 비밀 저장소 오류.
 *
 * <p>메시지에는 절대 비밀 값 자체를 넣지 않는다. 별칭과 원인만 남긴다.
 */
public class SecretStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
