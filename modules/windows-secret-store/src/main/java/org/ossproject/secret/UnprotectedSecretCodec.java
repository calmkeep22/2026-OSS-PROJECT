package org.ossproject.secret;

/**
 * 개발용 대체 구현. <b>암호화하지 않는다.</b>
 *
 * <p>macOS 나 Linux 에서 화면과 주문 흐름을 개발할 수 있도록 두었다. 실제 API 키를 넣으면
 * 평문으로 디스크에 남는다. {@link #isHardwareBacked()} 가 거짓이므로 화면 계층은 반드시
 * 사용자에게 경고를 표시해야 한다.
 *
 * <p>Windows 이외의 운영체제를 정식 지원하게 되면 각 OS의 키체인·키링으로 교체한다.
 */
public final class UnprotectedSecretCodec implements SecretCodec {

    @Override
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("값은 필수입니다.");
        }
        return plaintext.clone();
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("값은 필수입니다.");
        }
        return ciphertext.clone();
    }

    @Override
    public boolean isHardwareBacked() {
        return false;
    }

    @Override
    public String description() {
        return "암호화 없음 (개발용). 실제 API 키를 저장하지 마세요.";
    }
}
