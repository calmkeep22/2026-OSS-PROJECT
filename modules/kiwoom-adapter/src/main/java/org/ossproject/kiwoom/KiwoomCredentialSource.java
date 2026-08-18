package org.ossproject.kiwoom;

import org.ossproject.broker.BrokerCredentials;
import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretStore;

import java.util.Optional;

/**
 * 앱 키를 어디서 읽을지 정한다.
 *
 * <p>키를 코드나 저장소에 넣지 않고 외부에서 주입하기 위한 통로다. 우선순위는
 * <b>비밀 저장소 → 환경 변수</b> 순이다. 개발 중에는 환경 변수가 편하고, 실제 사용자는
 * 운영체제 보안 저장소를 쓰게 된다.
 *
 * <p>환경 변수 이름은 키움 공식 예제의 {@code .env.example} 을 따른다.
 * <pre>
 *   실전    APP_KEY,      APP_SECRET
 *   모의투자 APP_KEY_MOCK, APP_SECRET_MOCK
 * </pre>
 */
public final class KiwoomCredentialSource {

    private final SecretStore secretStore;

    /** 비밀 저장소 없이 환경 변수만 사용한다. */
    public KiwoomCredentialSource() {
        this(null);
    }

    public KiwoomCredentialSource(SecretStore secretStore) {
        this.secretStore = secretStore;
    }

    /**
     * 해당 환경의 앱 키를 찾는다.
     *
     * <p>돌려받은 자격 증명은 다 쓰면 {@link BrokerCredentials#close()} 로 닫아야 한다.
     */
    public Optional<BrokerCredentials> find(KiwoomEnvironment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("접속 환경은 필수입니다.");
        }

        Optional<BrokerCredentials> fromStore = fromSecretStore(environment);
        if (fromStore.isPresent()) {
            return fromStore;
        }
        return fromEnvironment(environment);
    }

    /**
     * 앱 키를 비밀 저장소에 넣는다. 설정 화면이 사용자 입력을 받아 호출한다.
     *
     * <p>호출자가 넘긴 배열은 이 메서드가 지운다. 화면 계층이 지우는 것을 잊어도
     * 평문이 오래 남지 않게 하기 위해서다.
     */
    public void save(KiwoomEnvironment environment, char[] appKey, char[] appSecret) {
        if (secretStore == null) {
            throw new IllegalStateException("비밀 저장소가 없어 앱 키를 보관할 수 없습니다.");
        }
        if (environment == null) {
            throw new IllegalArgumentException("접속 환경은 필수입니다.");
        }
        try {
            secretStore.store(environment.appKeyAlias(), appKey);
            secretStore.store(environment.appSecretAlias(), appSecret);
        } finally {
            SecretBytes.wipe(appKey);
            SecretBytes.wipe(appSecret);
        }
    }

    /** 저장된 앱 키를 지운다. */
    public void clear(KiwoomEnvironment environment) {
        if (secretStore == null) {
            return;
        }
        secretStore.delete(environment.appKeyAlias());
        secretStore.delete(environment.appSecretAlias());
    }

    private Optional<BrokerCredentials> fromSecretStore(KiwoomEnvironment environment) {
        if (secretStore == null || !secretStore.isAvailable()) {
            return Optional.empty();
        }
        Optional<char[]> key = secretStore.load(environment.appKeyAlias());
        Optional<char[]> secret = secretStore.load(environment.appSecretAlias());
        if (key.isEmpty() || secret.isEmpty()) {
            key.ifPresent(SecretBytes::wipe);
            secret.ifPresent(SecretBytes::wipe);
            return Optional.empty();
        }
        try {
            return Optional.of(BrokerCredentials.of(key.get(), secret.get()));
        } finally {
            SecretBytes.wipe(key.get());
            SecretBytes.wipe(secret.get());
        }
    }

    private Optional<BrokerCredentials> fromEnvironment(KiwoomEnvironment environment) {
        String suffix = environment == KiwoomEnvironment.MOCK ? "_MOCK" : "";
        String key = System.getenv("APP_KEY" + suffix);
        String secret = System.getenv("APP_SECRET" + suffix);
        if (key == null || key.isBlank() || secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(BrokerCredentials.of(key, secret));
    }
}
