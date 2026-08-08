package org.ossproject.secret;

import java.nio.file.Path;

/**
 * 운영체제에 맞는 비밀 저장소를 고른다.
 *
 * <p>Windows 면 DPAPI, 그 외에는 암호화하지 않는 개발용 구현을 쓴다. 어느 쪽인지는
 * {@link SecretStore#isHardwareBacked()} 로 확인할 수 있고, 화면 계층은 이 값이 거짓이면
 * 사용자에게 실제 API 키를 넣지 말라고 알려야 한다.
 */
public final class SecretStoreFactory {

    /** 기본 저장 위치. {@code ~/.accessible-investor/secrets} */
    public static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home", "."), ".accessible-investor", "secrets");
    }

    private SecretStoreFactory() {
    }

    public static SecretStore create() {
        return create(defaultDirectory());
    }

    public static SecretStore create(Path directory) {
        return new FileSecretStore(directory, createCodec());
    }

    /**
     * 운영체제에 맞는 암호화 구현.
     *
     * <p>Windows 에서 DPAPI 초기화에 실패하면 조용히 평문으로 넘어가지 않고 예외를 던진다.
     * 보안 기능이 꺼진 줄 모르고 실제 키를 저장하는 상황이 가장 위험하기 때문이다.
     */
    public static SecretCodec createCodec() {
        if (DpapiSecretCodec.isSupported()) {
            return new DpapiSecretCodec();
        }
        return new UnprotectedSecretCodec();
    }
}
