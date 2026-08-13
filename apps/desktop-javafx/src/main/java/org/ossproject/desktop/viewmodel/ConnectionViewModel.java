package org.ossproject.desktop.viewmodel;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** 키움 연결 화면의 입력 검증과 표시 상태. 실제 연결은 추후 Application Port에 위임한다. */
public final class ConnectionViewModel {
    public enum Environment { MOCK("모의투자"), LIVE("실전투자");
        private final String label;
        Environment(String label) { this.label = label; }
        public String label() { return label; }
    }

    private final ObjectProperty<Environment> environment = new SimpleObjectProperty<>(Environment.MOCK);
    private final StringProperty connectionMessage = new SimpleStringProperty("모의투자 서버에 연결되어 있습니다.");
    private final StringProperty connectionTone = new SimpleStringProperty("success");
    private final StringProperty tokenExpiry = new SimpleStringProperty("2026-08-11 09:00");
    private final StringProperty defaultAccount = new SimpleStringProperty("모의계좌");

    public ObjectProperty<Environment> environmentProperty() { return environment; }
    public StringProperty connectionMessageProperty() { return connectionMessage; }
    public StringProperty connectionToneProperty() { return connectionTone; }
    public StringProperty tokenExpiryProperty() { return tokenExpiry; }
    public StringProperty defaultAccountProperty() { return defaultAccount; }

    public boolean testConnection(String appKey, String appSecret) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            connectionMessage.set("App Key와 App Secret을 모두 입력해주세요.");
            connectionTone.set("warning");
            return false;
        }
        connectionMessage.set(environment.get().label() + " 서버 연결 테스트에 성공했습니다. 실제 API는 호출하지 않았습니다.");
        connectionTone.set("success");
        return true;
    }

    public void reissueDemoToken() {
        tokenExpiry.set("2026-08-11 18:00");
        connectionMessage.set("데모 토큰 만료 시각을 갱신했습니다.");
        connectionTone.set("success");
    }

    public void revokeToken() {
        tokenExpiry.set("폐기됨");
        connectionMessage.set("토큰이 없어 연결되지 않았습니다.");
        connectionTone.set("error");
    }

    public void selectDefaultAccount(String accountName) {
        if (accountName != null && !accountName.isBlank()) defaultAccount.set(accountName);
    }
}
