package org.ossproject.secret;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

/**
 * Windows DPAPI 기반 암·복호화.
 *
 * <p>{@code CryptProtectData} 를 CurrentUser 범위로 호출한다. 로그인한 사용자 계정에 묶인
 * 키로 암호화되므로, 파일을 그대로 복사해 가도 다른 계정이나 다른 PC에서는 풀 수 없다.
 * 우리가 직접 키를 만들지도, 저장하지도 않는다.
 *
 * <p>추가 엔트로피를 함께 넣어, 같은 PC의 다른 프로그램이 우연히 이 파일을 복호화하는
 * 상황도 막는다.
 *
 * <p>{@code CRYPTPROTECT_UI_FORBIDDEN} 을 지정해 백그라운드에서 대화상자가 뜨지 않게 한다.
 * 화면을 볼 수 없는 사용자에게 예고 없는 시스템 대화상자는 그 자체로 장애물이다.
 */
public final class DpapiSecretCodec implements SecretCodec {

    /** 프로그램 고유 엔트로피. 값이 바뀌면 기존에 저장한 비밀은 풀 수 없다. */
    private static final byte[] ENTROPY =
            "org.ossproject.accessible-investor.v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    /** Windows 가 아니면 만들 수 없다. */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    public DpapiSecretCodec() {
        if (!isSupported()) {
            throw new SecretStoreException(
                    "DPAPI 는 Windows 에서만 사용할 수 있습니다. 현재 운영체제 "
                            + System.getProperty("os.name"));
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("암호화할 값은 필수입니다.");
        }
        try {
            return Crypt32Util.cryptProtectData(plaintext, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN,
                    "accessible-investor", (WinCrypt.CRYPTPROTECT_PROMPTSTRUCT) null);
        } catch (RuntimeException e) {
            throw new SecretStoreException("DPAPI 암호화에 실패했습니다.", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("복호화할 값은 필수입니다.");
        }
        try {
            return Crypt32Util.cryptUnprotectData(ciphertext, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN,
                    (WinCrypt.CRYPTPROTECT_PROMPTSTRUCT) null);
        } catch (RuntimeException e) {
            throw new SecretStoreException(
                    "DPAPI 복호화에 실패했습니다. 다른 사용자 계정이나 다른 PC에서 만든 파일일 수 있습니다.", e);
        }
    }

    @Override
    public boolean isHardwareBacked() {
        return true;
    }

    @Override
    public String description() {
        return "Windows DPAPI (현재 사용자 계정 기준 암호화)";
    }
}
