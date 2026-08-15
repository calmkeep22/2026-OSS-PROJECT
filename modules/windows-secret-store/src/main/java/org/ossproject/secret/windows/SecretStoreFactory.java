package org.ossproject.secret.windows;

import org.ossproject.secret.SecretStore;
import org.ossproject.secret.SecretStoreException;
import org.ossproject.secret.file.FileSecretStore;

import java.nio.file.Path;

/** Creates a Windows DPAPI-backed secret store. */
public final class SecretStoreFactory {
    private SecretStoreFactory() {
    }

    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".accessible-investor", "secrets");
    }

    public static SecretStore create() {
        return create(defaultDirectory());
    }

    /** Creates a protected store or fails closed without a plaintext fallback. */
    public static SecretStore create(Path directory) {
        if (!DpapiSecretCodec.isSupported()) {
            throw new SecretStoreException(
                    "Windows DPAPI secret storage is not supported on this operating system.");
        }
        return new FileSecretStore(directory, new DpapiSecretCodec());
    }
}
