package org.ossproject.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialsTest {

    private static final Instant START = Instant.parse("2026-08-08T01:00:00Z");

    @Test
    @DisplayName("자격 증명을 닫으면 메모리에서 지워지고 이후 접근이 막힌다")
    void clearsOnClose() {
        BrokerCredentials credentials = BrokerCredentials.of("PS1a2b3c4d5e", "S9z8y7x6w5v4");
        assertEquals("PS1a2b3c4d5e", credentials.appKey());

        credentials.close();

        assertTrue(credentials.isCleared());
        assertThrows(IllegalStateException.class, credentials::appKey);
        assertThrows(IllegalStateException.class, credentials::appSecret);
    }

    @Test
    @DisplayName("입력 배열을 복사해서 보관하므로 원본을 지워도 영향이 없다")
    void copiesInputArray() {
        char[] key = "PS1a2b3c4d5e".toCharArray();
        char[] secret = "S9z8y7x6w5v4".toCharArray();
        BrokerCredentials credentials = BrokerCredentials.of(key, secret);

        Arrays.fill(key, '\0');
        Arrays.fill(secret, '\0');

        assertEquals("PS1a2b3c4d5e", credentials.appKey());
        credentials.close();
    }

    @Test
    @DisplayName("toString 은 키를 노출하지 않는다")
    void doesNotLeakInToString() {
        try (BrokerCredentials credentials = BrokerCredentials.of("PS1a2b3c4d5e", "S9z8y7x6w5v4")) {
            String text = credentials.toString();

            assertFalse(text.contains("PS1a2b3c4d5e"));
            assertFalse(text.contains("S9z8y7x6w5v4"));
        }
    }

    @Test
    @DisplayName("빈 키는 거부한다")
    void rejectsBlankKey() {
        assertThrows(IllegalArgumentException.class, () -> BrokerCredentials.of("", "secret"));
        assertThrows(IllegalArgumentException.class, () -> BrokerCredentials.of("key", " "));
    }

    @Test
    @DisplayName("토큰 만료와 갱신 시점을 판단한다")
    void detectsTokenExpiry() {
        AccessToken token = new AccessToken("eyJhbGciOiJIUzI1NiJ9.payload", START.plus(Duration.ofMinutes(10)));
        TestClock clock = new TestClock(START);

        assertFalse(token.isExpired(clock));
        assertFalse(token.needsRefresh(clock));

        clock.advance(Duration.ofMinutes(9));
        assertFalse(token.isExpired(clock));
        assertTrue(token.needsRefresh(clock));

        clock.advance(Duration.ofMinutes(1));
        assertTrue(token.isExpired(clock));
    }

    @Test
    @DisplayName("토큰 toString 은 값을 가린다")
    void masksTokenInToString() {
        AccessToken token = new AccessToken("eyJhbGciOiJIUzI1NiJ9.payload", START.plusSeconds(60));

        assertFalse(token.toString().contains("payload"));
        assertTrue(token.asBearerHeader().startsWith("Bearer "));
    }
}
