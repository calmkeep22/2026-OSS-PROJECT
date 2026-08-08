package org.ossproject.secret;

/**
 * 비밀 값의 암·복호화.
 *
 * <p>Windows 에서는 DPAPI 가 구현하고, 다른 운영체제에서는 개발용 대체 구현이 들어간다.
 * 파일 저장 로직과 분리해 두면 암호화 방식이 바뀌어도 저장소 코드는 그대로 쓸 수 있고,
 * Windows 가 아닌 환경에서도 저장소 동작을 검증할 수 있다.
 */
public interface SecretCodec {

    byte[] encrypt(byte[] plaintext);

    byte[] decrypt(byte[] ciphertext);

    /** 운영체제의 보안 기능으로 실제 보호하는지 여부. */
    boolean isHardwareBacked();

    String description();
}
