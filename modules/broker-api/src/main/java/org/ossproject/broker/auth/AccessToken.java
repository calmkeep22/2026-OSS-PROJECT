package org.ossproject.broker.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 증권사 액세스 토큰.
 *
 * <p>디스크에 저장하지 않고 메모리에만 둔다. {@link #toString()} 은 값을 가린다.
 */
public record AccessToken(String value, Instant expiresAt) {

    /** 만료 직전에 미리 갱신하기 위한 여유 시간. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);

    public AccessToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("토큰 값은 필수입니다.");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("만료 시각은 필수입니다.");
        }
    }

    public boolean isExpired(Clock clock) {
        return !clock.instant().isBefore(expiresAt);
    }

    /** 만료 1분 전부터는 갱신해야 한다고 본다. */
    public boolean needsRefresh(Clock clock) {
        return !clock.instant().isBefore(expiresAt.minus(REFRESH_MARGIN));
    }

    /** {@code Authorization} 헤더 값. */
    public String asBearerHeader() {
        return "Bearer " + value;
    }

    @Override
    public String toString() {
        return "AccessToken{value=" + SensitiveDataMasker.maskSecret(value)
                + ", expiresAt=" + expiresAt + "}";
    }
}
