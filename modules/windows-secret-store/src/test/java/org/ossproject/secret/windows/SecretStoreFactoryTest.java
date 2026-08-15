package org.ossproject.secret.windows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.SecretStoreException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretStoreFactoryTest {
    @Test
    void usesDpapiOrFailsClosed(@TempDir Path directory) {
        if (!DpapiSecretCodec.isSupported()) {
            assertThrows(SecretStoreException.class, () -> SecretStoreFactory.create(directory));
            return;
        }

        try (SecretStore store = SecretStoreFactory.create(directory)) {
            store.store("key", "value".toCharArray());
            assertTrue(store.isAvailable());
            assertTrue(store.protectionLevel() == SecretProtectionLevel.OS_USER_PROTECTED);
            assertArrayEquals("value".toCharArray(), store.load("key").orElseThrow());
        }
    }
}
