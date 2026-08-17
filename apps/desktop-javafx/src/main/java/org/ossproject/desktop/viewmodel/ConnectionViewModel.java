package org.ossproject.desktop.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretStore;

import java.util.Objects;

/** 키움 연결 화면의 입력 검증과 보호된 로컬 자격증명 상태. */
public final class ConnectionViewModel {
    public enum Environment { MOCK("모의투자"), LIVE("실전투자");
        private final String label;
        Environment(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final ObjectProperty<Environment> environment = new SimpleObjectProperty<>(Environment.MOCK);
    private final StringProperty connectionMessage = new SimpleStringProperty(
            "자격증명과 API 연결을 아직 확인하지 않았습니다. 현재 화면은 로컬 데모입니다.");
    private final StringProperty connectionTone = new SimpleStringProperty("warning");
    private final StringProperty tokenExpiry = new SimpleStringProperty("발급 전 · 실제 API 미연결");
    private final StringProperty defaultAccount = new SimpleStringProperty("모의계좌");
    private final StringProperty credentialStorageDescription = new SimpleStringProperty();
    private final StringProperty credentialStorageStatus = new SimpleStringProperty();
    private final BooleanProperty storedCredentials = new SimpleBooleanProperty();
    private final SecretStore secretStore;

    public ConnectionViewModel(SecretStore secretStore) {
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        environment.addListener((obs, old, selected) -> refreshCredentialStorageState());
        refreshCredentialStorageState();
    }

    public ObjectProperty<Environment> environmentProperty() { return environment; }
    public StringProperty connectionMessageProperty() { return connectionMessage; }
    public StringProperty connectionToneProperty() { return connectionTone; }
    public StringProperty tokenExpiryProperty() { return tokenExpiry; }
    public StringProperty defaultAccountProperty() { return defaultAccount; }
    public StringProperty credentialStorageDescriptionProperty() { return credentialStorageDescription; }
    public StringProperty credentialStorageStatusProperty() { return credentialStorageStatus; }
    public ReadOnlyBooleanProperty storedCredentialsProperty() { return storedCredentials; }
    public boolean isCredentialStorageAvailable() { return secretStore.isAvailable(); }

    /**
     * Validates typed credentials or, when both fields are blank, protected stored credentials.
     * No broker request is sent yet.
     */
    public boolean testConnection(String appKey, char[] appSecret, boolean rememberCredentials) {
        char[] stored = null;
        try {
            boolean typedKey = appKey != null && !appKey.isBlank();
            boolean typedSecret = !isBlank(appSecret);
            if (typedKey != typedSecret) {
                return fail("App Key와 App Secret을 모두 입력하거나 모두 비워주세요.");
            }

            boolean usingStored = !typedKey;
            if (usingStored) {
                if (!secretStore.isAvailable() || !storedCredentials.get()) {
                    return fail("저장된 자격증명이 없습니다. App Key와 App Secret을 입력해주세요.");
                }
                stored = secretStore.load(alias()).orElse(null);
                int separator = stored == null ? -1 : separatorIndex(stored);
                if (separator < 1 || separator >= stored.length - 1) {
                    return fail("저장된 자격증명을 읽지 못했습니다. 다시 입력해주세요.");
                }
            } else if (rememberCredentials) {
                if (!secretStore.isAvailable()) {
                    return fail("보호된 비밀 저장소를 사용할 수 없어 자격증명을 저장하지 않았습니다.");
                }
                char[] packed = pack(appKey, appSecret);
                try {
                    secretStore.store(alias(), packed);
                } finally {
                    SecretBytes.wipe(packed);
                }
                refreshCredentialStorageState();
            }

            String storageResult = usingStored
                    ? "저장된 자격증명을 사용했습니다. "
                    : rememberCredentials ? "보호된 저장소에 저장했습니다. " : "저장하지 않았습니다. ";
            connectionMessage.set(environment.get().label() + " 자격증명을 확인했습니다. "
                    + storageResult + "실제 API는 호출하지 않았습니다.");
            connectionTone.set("success");
            return true;
        } catch (RuntimeException error) {
            return fail("자격증명 저장소 오류: " + safeMessage(error));
        } finally {
            SecretBytes.wipe(stored);
        }
    }

    public boolean deleteStoredCredentials() {
        if (!secretStore.isAvailable()) return fail("보호된 비밀 저장소를 사용할 수 없습니다.");
        try {
            secretStore.delete(alias());
            refreshCredentialStorageState();
            connectionMessage.set(environment.get().label() + " 자격증명을 보호된 저장소에서 삭제했습니다.");
            connectionTone.set("success");
            return true;
        } catch (RuntimeException error) {
            return fail("저장된 자격증명을 삭제하지 못했습니다: " + safeMessage(error));
        }
    }

    public void reissueDemoToken() {
        tokenExpiry.set("데모 발급됨 · 실제 토큰 아님");
        connectionMessage.set("화면 확인용 데모 토큰 상태를 만들었습니다. 실제 API는 호출하지 않았습니다.");
        connectionTone.set("warning");
    }

    public void revokeToken() {
        tokenExpiry.set("폐기됨");
        connectionMessage.set("토큰이 없어 연결되지 않았습니다.");
        connectionTone.set("error");
    }

    public void selectDefaultAccount(String accountName) {
        if (accountName != null && !accountName.isBlank()) defaultAccount.set(accountName);
    }

    private void refreshCredentialStorageState() {
        credentialStorageDescription.set(secretStore.protectionLevel().displayName()
                + " · " + secretStore.description());
        boolean stored = secretStore.isAvailable() && secretStore.contains(alias());
        storedCredentials.set(stored);
        credentialStorageStatus.set(stored ? "현재 환경의 자격증명이 저장되어 있습니다."
                : secretStore.isAvailable() ? "현재 환경에 저장된 자격증명이 없습니다."
                : "보호된 비밀 저장소를 사용할 수 없습니다.");
    }

    private String alias() {
        return "kiwoom." + environment.get().name().toLowerCase(java.util.Locale.ROOT) + ".credentials";
    }

    private boolean fail(String message) {
        connectionMessage.set(message);
        connectionTone.set("warning");
        return false;
    }

    private static char[] pack(String appKey, char[] appSecret) {
        if (appKey.indexOf('\n') >= 0 || appKey.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("App Key에는 줄바꿈을 사용할 수 없습니다.");
        }
        char[] key = appKey.trim().toCharArray();
        char[] packed = new char[key.length + 1 + appSecret.length];
        try {
            System.arraycopy(key, 0, packed, 0, key.length);
            packed[key.length] = '\n';
            System.arraycopy(appSecret, 0, packed, key.length + 1, appSecret.length);
            return packed;
        } finally {
            SecretBytes.wipe(key);
        }
    }

    private static int separatorIndex(char[] value) {
        for (int index = 0; index < value.length; index++) {
            if (value[index] == '\n') return index;
        }
        return -1;
    }

    private static boolean isBlank(char[] value) {
        if (value == null || value.length == 0) return true;
        for (char character : value) {
            if (!Character.isWhitespace(character)) return false;
        }
        return true;
    }

    private static String safeMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }
}
