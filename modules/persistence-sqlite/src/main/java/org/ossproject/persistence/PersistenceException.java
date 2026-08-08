package org.ossproject.persistence;

/**
 * 저장소 오류.
 *
 * <p>메시지는 화면과 음성 안내에 쓰이므로 한국어로 작성하고, 계좌번호나 키가 섞이지 않도록
 * 주의한다.
 */
public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
