package org.ossproject.secret;

/** Failure to protect, persist, load, or delete a secret. */
public class SecretStoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
