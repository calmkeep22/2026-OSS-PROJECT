package org.ossproject.desktop.composition;

import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.SecretStoreException;

import java.util.Optional;
import java.util.Set;

/** Fail-closed desktop fallback used when the operating-system store cannot be created. */
final class UnavailableSecretStore implements SecretStore {
    private final String reason;

    UnavailableSecretStore(String reason) {
        this.reason = reason == null || reason.isBlank()
                ? "보호된 비밀 저장소를 사용할 수 없습니다."
                : reason;
    }

    @Override public void store(String alias, char[] secret) { throw unavailable(); }
    @Override public Optional<char[]> load(String alias) { throw unavailable(); }
    @Override public void delete(String alias) { throw unavailable(); }
    @Override public boolean contains(String alias) { return false; }
    @Override public Set<String> aliases() { return Set.of(); }
    @Override public SecretProtectionLevel protectionLevel() { return SecretProtectionLevel.UNAVAILABLE; }
    @Override public String description() { return reason; }
    @Override public void close() { }

    private SecretStoreException unavailable() {
        return new SecretStoreException(reason);
    }
}
