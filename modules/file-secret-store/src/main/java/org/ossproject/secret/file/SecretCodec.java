package org.ossproject.secret.file;

import org.ossproject.secret.SecretProtectionLevel;

/** Encryption boundary used by the file-backed secret store. */
public interface SecretCodec {
    /**
     * Returns a newly allocated protected buffer. The implementation must not retain the input.
     * The caller owns and clears both the input and returned buffers.
     */
    byte[] encrypt(byte[] plaintext);

    /**
     * Returns a newly allocated plaintext buffer. The implementation must not retain the input.
     * The caller owns and clears both the input and returned buffers.
     */
    byte[] decrypt(byte[] ciphertext);

    SecretProtectionLevel protectionLevel();

    String description();
}
