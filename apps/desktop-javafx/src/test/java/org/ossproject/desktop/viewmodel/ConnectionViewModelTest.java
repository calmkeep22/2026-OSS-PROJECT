package org.ossproject.desktop.viewmodel;

import org.junit.jupiter.api.Test;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionViewModelTest {

    @Test
    void storesUsesAndDeletesCredentialsPerEnvironment() {
        MemorySecretStore store = new MemorySecretStore();
        ConnectionViewModel viewModel = new ConnectionViewModel(store);

        assertTrue(viewModel.testConnection("mock-key", "mock-secret".toCharArray(), true));
        assertTrue(store.contains("kiwoom.mock.credentials"));
        assertTrue(viewModel.storedCredentialsProperty().get());

        viewModel.environmentProperty().set(ConnectionViewModel.Environment.LIVE);
        assertFalse(viewModel.storedCredentialsProperty().get());
        assertFalse(viewModel.testConnection("", new char[0], false));

        viewModel.environmentProperty().set(ConnectionViewModel.Environment.MOCK);
        assertTrue(viewModel.testConnection("", new char[0], false));
        assertTrue(viewModel.deleteStoredCredentials());
        assertFalse(store.contains("kiwoom.mock.credentials"));
    }

    @Test
    void doesNotPersistWhenRememberIsDisabled() {
        MemorySecretStore store = new MemorySecretStore();
        ConnectionViewModel viewModel = new ConnectionViewModel(store);

        assertTrue(viewModel.testConnection("temporary-key", "temporary-secret".toCharArray(), false));
        assertTrue(store.aliases().isEmpty());
    }

    @Test
    void rejectsIncompleteCredentials() {
        ConnectionViewModel viewModel = new ConnectionViewModel(new MemorySecretStore());

        assertFalse(viewModel.testConnection("only-key", new char[0], true));
        assertFalse(viewModel.testConnection("", "only-secret".toCharArray(), true));
    }

    private static final class MemorySecretStore implements SecretStore {
        private final Map<String, char[]> values = new LinkedHashMap<>();

        @Override public void store(String alias, char[] secret) { values.put(alias, secret.clone()); }
        @Override public Optional<char[]> load(String alias) {
            char[] value = values.get(alias);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public void delete(String alias) { values.remove(alias); }
        @Override public boolean contains(String alias) { return values.containsKey(alias); }
        @Override public Set<String> aliases() { return Set.copyOf(values.keySet()); }
        @Override public SecretProtectionLevel protectionLevel() {
            return SecretProtectionLevel.OS_USER_PROTECTED;
        }
        @Override public String description() { return "test store"; }
        @Override public void close() { values.clear(); }
    }
}
