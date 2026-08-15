package org.ossproject.secret.windows;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStoreException;
import org.ossproject.secret.file.SecretCodec;

/**
 * Windows DPAPI codec bound to the current user account.
 *
 * <p>DPAPI protects data with operating-system managed user credentials. It does not guarantee
 * that the underlying key is hardware-backed, so this codec reports
 * {@link SecretProtectionLevel#OS_USER_PROTECTED}.</p>
 */
public final class DpapiSecretCodec implements SecretCodec {

    private static final byte[] ENTROPY =
            "org.ossproject.accessible-investor.v1"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    public static boolean isSupported() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
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
        } catch (RuntimeException error) {
            throw new SecretStoreException("DPAPI 암호화에 실패했습니다.", error);
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
        } catch (RuntimeException error) {
            throw new SecretStoreException(
                    "DPAPI 복호화에 실패했습니다. 다른 사용자 계정이나 다른 PC에서 만든 파일일 수 있습니다.",
                    error);
        }
    }

    @Override
    public SecretProtectionLevel protectionLevel() {
        return SecretProtectionLevel.OS_USER_PROTECTED;
    }

    @Override
    public String description() {
        return "Windows DPAPI (현재 사용자 계정 기준 암호화)";
    }
}
