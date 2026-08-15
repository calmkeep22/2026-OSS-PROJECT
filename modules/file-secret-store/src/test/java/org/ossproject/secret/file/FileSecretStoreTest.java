package org.ossproject.secret.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.contract.SecretStoreContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSecretStoreTest extends SecretStoreContract {
    private static final class ReversingCodec implements SecretCodec {
        @Override
        public byte[] encrypt(byte[] plaintext) {
            byte[] copy = plaintext.clone();
            for (int i = 0; i < copy.length / 2; i++) {
                byte swap = copy[i];
                copy[i] = copy[copy.length - 1 - i];
                copy[copy.length - 1 - i] = swap;
            }
            return copy;
        }

        @Override
        public byte[] decrypt(byte[] ciphertext) {
            return encrypt(ciphertext);
        }

        @Override
        public SecretProtectionLevel protectionLevel() {
            return SecretProtectionLevel.SOFTWARE_ENCRYPTED;
        }

        @Override
        public String description() {
            return "test codec";
        }
    }

    private FileSecretStore store(Path directory) {
        return new FileSecretStore(directory, new ReversingCodec());
    }

    @Override
    protected SecretStore createStore(Path directory) {
        return store(directory);
    }

    @Test
    void storesLoadsOverwritesAndLists(@TempDir Path directory) {
        FileSecretStore store = store(directory);
        store.store("kiwoom-key", "first".toCharArray());
        store.store("kiwoom-key", "두번째".toCharArray());

        assertArrayEquals("두번째".toCharArray(), store.load("kiwoom-key").orElseThrow());
        assertEquals(Set.of("kiwoom-key"), store.aliases());
        assertTrue(store.contains("kiwoom-key"));
    }

    @Test
    void neverWritesPlaintext(@TempDir Path directory) throws Exception {
        FileSecretStore store = store(directory);
        store.store("key", "plain-secret-value".toCharArray());

        assertFalse(Files.readString(directory.resolve("key.secret")).contains("plain-secret-value"));
    }

    @Test
    void rejectsTraversalAndEmptyValues(@TempDir Path directory) {
        FileSecretStore store = store(directory);

        assertThrows(IllegalArgumentException.class,
                () -> store.store("../outside", "x".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> store.store("key", new char[0]));
    }

    @Test
    void deletesStoredValue(@TempDir Path directory) {
        FileSecretStore store = store(directory);
        store.store("key", "value".toCharArray());

        store.delete("key");

        assertTrue(store.load("key").isEmpty());
    }

    @Test
    void convertsAndWipesMutableBuffers() {
        char[] chars = "비밀-secret".toCharArray();
        byte[] bytes = SecretBytes.toBytes(chars);

        assertArrayEquals(chars, SecretBytes.toChars(bytes));
        SecretBytes.wipe(chars);
        SecretBytes.wipe(bytes);
        assertTrue(new String(chars).chars().allMatch(value -> value == 0));
        assertTrue(new String(bytes).chars().allMatch(value -> value == 0));
    }
}
