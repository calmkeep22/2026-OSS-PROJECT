package org.ossproject.broker;

import java.util.Arrays;

/**
 * 증권사 API 키와 시크릿.
 *
 * <p>{@code String} 은 불변이라 GC 되기 전까지 힙에 남고 힙 덤프에 그대로 찍힌다.
 * 그래서 {@code char[]} 로 들고 있다가 {@link #close()} 에서 0으로 덮어쓴다.
 * 값이 필요한 순간에만 문자열로 만든다.
 *
 * <p>이 객체는 절대 직렬화하거나 로그에 남기지 않는다. {@link #toString()} 은 가려진
 * 값만 돌려준다.
 */
public final class BrokerCredentials implements AutoCloseable {

    private final char[] appKey;
    private final char[] appSecret;
    private volatile boolean cleared;

    private BrokerCredentials(char[] appKey, char[] appSecret) {
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    /** 배열을 복사해서 보관한다. 호출자는 원본을 스스로 지워야 한다. */
    public static BrokerCredentials of(char[] appKey, char[] appSecret) {
        requireNotBlank(appKey, "API 키");
        requireNotBlank(appSecret, "API 시크릿");
        return new BrokerCredentials(appKey.clone(), appSecret.clone());
    }

    /**
     * 문자열에서 만든다. 설정 파일이나 사용자 입력을 받을 때 쓰지만,
     * 넘긴 문자열 자체는 이 클래스가 지울 수 없다는 점에 주의한다.
     */
    public static BrokerCredentials of(String appKey, String appSecret) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalArgumentException("API 키는 필수입니다.");
        }
        if (appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("API 시크릿은 필수입니다.");
        }
        return new BrokerCredentials(appKey.toCharArray(), appSecret.toCharArray());
    }

    public String appKey() {
        requireUsable();
        return new String(appKey);
    }

    public String appSecret() {
        requireUsable();
        return new String(appSecret);
    }

    /** DPAPI 같은 보안 저장소에 넘길 때 쓴다. 호출자가 지워야 한다. */
    public char[] appSecretChars() {
        requireUsable();
        return appSecret.clone();
    }

    public boolean isCleared() {
        return cleared;
    }

    /** 메모리에서 값을 지운다. 이후 접근하면 예외가 난다. */
    @Override
    public void close() {
        Arrays.fill(appKey, '\0');
        Arrays.fill(appSecret, '\0');
        cleared = true;
    }

    @Override
    public String toString() {
        return "BrokerCredentials{appKey=" + (cleared ? "지워짐" : SensitiveDataMasker.maskSecret(new String(appKey)))
                + ", appSecret=****}";
    }

    private void requireUsable() {
        if (cleared) {
            throw new IllegalStateException("이미 지워진 자격 증명입니다.");
        }
    }

    private static void requireNotBlank(char[] value, String label) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(label + "는 필수입니다.");
        }
    }
}
