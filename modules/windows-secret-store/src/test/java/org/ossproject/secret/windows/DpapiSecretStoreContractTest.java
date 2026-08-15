package org.ossproject.secret.windows;

import org.junit.jupiter.api.Assumptions;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.contract.SecretStoreContract;

import java.nio.file.Path;

class DpapiSecretStoreContractTest extends SecretStoreContract {
    @Override
    protected SecretStore createStore(Path directory) {
        Assumptions.assumeTrue(DpapiSecretCodec.isSupported(), "Windows DPAPI is unavailable");
        return SecretStoreFactory.create(directory);
    }
}
