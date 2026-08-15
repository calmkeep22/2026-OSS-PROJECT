package org.ossproject.secret;

/** Describes how a secret-store implementation protects persisted values. */
public enum SecretProtectionLevel {
    /** The store is a fail-closed placeholder and cannot persist values. */
    UNAVAILABLE("사용할 수 없음"),
    /** Values are encrypted by application-provided software keys. */
    SOFTWARE_ENCRYPTED("소프트웨어 암호화"),
    /** Values are protected by the current operating-system user account. */
    OS_USER_PROTECTED("운영체제 사용자 계정 보호"),
    /** Values are protected by a hardware-backed key facility. */
    HARDWARE_BACKED("하드웨어 키 보호");

    private final String displayName;

    SecretProtectionLevel(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isAvailable() {
        return this != UNAVAILABLE;
    }
}
