package org.ossproject.secret.file;

import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStoreException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM codec whose key is derived from a user-supplied passphrase.
 *
 * <p>Every value gets a new salt and nonce. The passphrase is never written to disk and is
 * cleared when the store closes. This is the protected, cross-platform fallback when an
 * operating-system credential vault is unavailable.
 */
public final class PassphraseSecretCodec implements SecretCodec {
    private static final byte[] MAGIC = {'O', 'S', 'S', 'K'};
    private static final byte VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int ITERATIONS = 210_000;
    private static final int HEADER_BYTES = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES;

    private final SecureRandom random;
    private final char[] passphrase;
    private boolean closed;

    public PassphraseSecretCodec(char[] passphrase) {
        this(passphrase, new SecureRandom());
    }

    PassphraseSecretCodec(char[] passphrase, SecureRandom random) {
        if (passphrase == null || passphrase.length < 12) {
            throw new IllegalArgumentException("Passphrase must contain at least 12 characters.");
        }
        this.passphrase = passphrase.clone();
        this.random = random;
    }

    @Override
    public synchronized byte[] encrypt(byte[] plaintext) {
        requireOpen();
        if (plaintext == null) throw new IllegalArgumentException("Plaintext is required.");
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] key = null;
        try {
            key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad());
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(HEADER_BYTES + encrypted.length)
                    .put(MAGIC).put(VERSION).put(salt).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException error) {
            throw new SecretStoreException("Could not encrypt a secret.", error);
        } finally {
            SecretBytes.wipe(key);
            SecretBytes.wipe(salt);
            SecretBytes.wipe(nonce);
        }
    }

    @Override
    public synchronized byte[] decrypt(byte[] ciphertext) {
        requireOpen();
        if (ciphertext == null || ciphertext.length <= HEADER_BYTES) {
            throw new SecretStoreException("Encrypted secret has an invalid format.");
        }
        ByteBuffer input = ByteBuffer.wrap(ciphertext);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        byte version = input.get();
        if (!Arrays.equals(magic, MAGIC) || version != VERSION) {
            throw new SecretStoreException("Encrypted secret has an unsupported format.");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] encrypted = new byte[input.remaining() - SALT_BYTES - NONCE_BYTES];
        input.get(salt).get(nonce).get(encrypted);
        byte[] key = null;
        try {
            key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad());
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException wrongPassphraseOrDamage) {
            throw new SecretStoreException("Secret could not be unlocked. Check the passphrase.",
                    wrongPassphraseOrDamage);
        } catch (GeneralSecurityException error) {
            throw new SecretStoreException("Could not decrypt a secret.", error);
        } finally {
            SecretBytes.wipe(key);
            SecretBytes.wipe(salt);
            SecretBytes.wipe(nonce);
            SecretBytes.wipe(encrypted);
        }
    }

    @Override
    public SecretProtectionLevel protectionLevel() {
        return SecretProtectionLevel.SOFTWARE_ENCRYPTED;
    }

    @Override
    public String description() {
        return "암호문구 기반 AES-256-GCM";
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(passphrase, '\0');
            closed = true;
        }
    }

    private byte[] deriveKey(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] aad() {
        return new byte[] {MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], VERSION};
    }

    private void requireOpen() {
        if (closed) throw new SecretStoreException("Secret codec is closed.");
    }
}
