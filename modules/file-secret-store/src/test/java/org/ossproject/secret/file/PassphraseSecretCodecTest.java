package org.ossproject.secret.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStoreException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassphraseSecretCodecTest {
    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @Test
    void roundTripsAndUsesFreshSaltAndNonce() {
        try (PassphraseSecretCodec codec = new PassphraseSecretCodec(PASSPHRASE)) {
            byte[] first = codec.encrypt("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] second = codec.encrypt("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            assertNotEquals(java.util.HexFormat.of().formatHex(first),
                    java.util.HexFormat.of().formatHex(second));
            assertArrayEquals("secret".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    codec.decrypt(first));
        }
    }

    @Test
    void rejectsWrongPassphrase() {
        byte[] encrypted;
        try (PassphraseSecretCodec writer = new PassphraseSecretCodec(PASSPHRASE)) {
            encrypted = writer.encrypt(new byte[] {1, 2, 3});
        }
        try (PassphraseSecretCodec reader = new PassphraseSecretCodec(
                "different passphrase value".toCharArray())) {
            assertThrows(SecretStoreException.class, () -> reader.decrypt(encrypted));
        }
    }

    @Test
    void refusesUseAfterKeyMaterialIsCleared() {
        PassphraseSecretCodec codec = new PassphraseSecretCodec(PASSPHRASE);
        codec.close();

        assertThrows(SecretStoreException.class, () -> codec.encrypt(new byte[] {1}));
        assertThrows(SecretStoreException.class, () -> codec.decrypt(new byte[] {1}));
    }

    @Test
    void fileStoreNeverPersistsPlaintext(@TempDir Path directory) throws Exception {
        try (FileSecretStore store = new FileSecretStore(directory,
                new PassphraseSecretCodec(PASSPHRASE))) {
            store.store("api-key", "visible-secret-value".toCharArray());
            byte[] persisted = Files.readAllBytes(directory.resolve("api-key.secret"));
            assertFalse(new String(persisted, java.nio.charset.StandardCharsets.UTF_8)
                    .contains("visible-secret-value"));
            assertArrayEquals("visible-secret-value".toCharArray(),
                    store.load("api-key").orElseThrow());
            assertNotEquals(SecretProtectionLevel.UNAVAILABLE, store.protectionLevel());
        }
    }
}
