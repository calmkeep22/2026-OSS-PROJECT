package org.ossproject.secret.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reusable behavioral contract for protected secret-store adapters. */
public abstract class SecretStoreContract {

    protected abstract SecretStore createStore(Path directory);

    @Test
    void storesLoadsOverwritesAndDeletes(@TempDir Path directory) {
        try (SecretStore store = createStore(directory)) {
            store.store("contract-key", "first".toCharArray());
            store.store("contract-key", "두번째".toCharArray());

            char[] loaded = store.load("contract-key").orElseThrow();
            try {
                assertArrayEquals("두번째".toCharArray(), loaded);
                assertTrue(store.contains("contract-key"));
                assertTrue(store.aliases().contains("contract-key"));
            } finally {
                SecretBytes.wipe(loaded);
            }

            store.delete("contract-key");
            assertFalse(store.contains("contract-key"));
            assertTrue(store.load("contract-key").isEmpty());
        }
    }

    @Test
    void isolatesMutableInputAndOutputBuffers(@TempDir Path directory) {
        try (SecretStore store = createStore(directory)) {
            char[] input = "original-secret".toCharArray();
            store.store("buffer-key", input);
            input[0] = 'X';

            char[] first = store.load("buffer-key").orElseThrow();
            first[0] = 'Y';
            char[] second = store.load("buffer-key").orElseThrow();
            try {
                assertArrayEquals("original-secret".toCharArray(), second);
            } finally {
                SecretBytes.wipe(input);
                SecretBytes.wipe(first);
                SecretBytes.wipe(second);
            }
        }
    }

    @Test
    void reportsSafeCapabilitiesAndClosesIdempotently(@TempDir Path directory) {
        SecretStore store = createStore(directory);
        store.store("safe-key", "must-not-appear".toCharArray());

        assertNotNull(store.protectionLevel());
        assertTrue(store.isAvailable());
        assertNotNull(store.description());
        assertFalse(store.description().contains("must-not-appear"));
        assertDoesNotThrow(store::close);
        assertDoesNotThrow(store::close);
    }
}
