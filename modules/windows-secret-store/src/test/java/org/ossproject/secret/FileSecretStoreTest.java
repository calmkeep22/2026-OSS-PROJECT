package org.ossproject.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSecretStoreTest {

    /** 바이트를 뒤집기만 하는 가짜 암호화. 저장 로직만 검증한다. */
    private static final class ReversingCodec implements SecretCodec {
        @Override
        public byte[] encrypt(byte[] plaintext) {
            byte[] copy = plaintext.clone();
            for (int i = 0; i < copy.length / 2; i++) {
                byte swap = copy[i];
                copy[i] = copy[copy.length - 1 - i];
                copy[copy.length - 1 - i] = swap;
            }
            return copy;
        }

        @Override
        public byte[] decrypt(byte[] ciphertext) {
            return encrypt(ciphertext);
        }

        @Override
        public boolean isHardwareBacked() {
            return true;
        }

        @Override
        public String description() {
            return "테스트용";
        }
    }

    private FileSecretStore store(Path directory) {
        return new FileSecretStore(directory, new ReversingCodec());
    }

    @Test
    @DisplayName("저장한 값을 그대로 읽어 온다")
    void storesAndLoads(@TempDir Path dir) {
        FileSecretStore store = store(dir);

        store.store("kiwoom-appkey", "PS1a2b3c4d5e".toCharArray());
        char[] loaded = store.load("kiwoom-appkey").orElseThrow();

        assertArrayEquals("PS1a2b3c4d5e".toCharArray(), loaded);
    }

    @Test
    @DisplayName("한글과 특수문자가 섞인 값도 손상 없이 복원된다")
    void handlesUnicode(@TempDir Path dir) {
        FileSecretStore store = store(dir);
        char[] secret = "비밀키!@#$%^&*()_+한글".toCharArray();

        store.store("unicode", secret.clone());

        assertArrayEquals(secret, store.load("unicode").orElseThrow());
    }

    @Test
    @DisplayName("디스크에는 평문이 남지 않는다")
    void doesNotWritePlaintext(@TempDir Path dir) throws Exception {
        FileSecretStore store = store(dir);
        store.store("kiwoom-appkey", "PS1a2b3c4d5e".toCharArray());

        Path file = dir.resolve("kiwoom-appkey.secret");
        String raw = Files.readString(file);

        assertFalse(raw.contains("PS1a2b3c4d5e"));
    }

    @Test
    @DisplayName("같은 별칭에 다시 저장하면 덮어쓴다")
    void overwritesExisting(@TempDir Path dir) {
        FileSecretStore store = store(dir);

        store.store("key", "first-value".toCharArray());
        store.store("key", "second-value".toCharArray());

        assertArrayEquals("second-value".toCharArray(), store.load("key").orElseThrow());
        assertEquals(1, store.aliases().size());
    }

    @Test
    @DisplayName("없는 별칭은 비어 있는 값을 돌려준다")
    void returnsEmptyForMissing(@TempDir Path dir) {
        assertEquals(Optional.empty(), store(dir).load("없음".replace("없음", "missing")));
    }

    @Test
    @DisplayName("지운 뒤에는 읽히지 않는다")
    void deletes(@TempDir Path dir) {
        FileSecretStore store = store(dir);
        store.store("key", "value".toCharArray());

        store.delete("key");

        assertFalse(store.contains("key"));
        assertTrue(store.load("key").isEmpty());
    }

    @Test
    @DisplayName("저장된 별칭 목록을 돌려준다")
    void listsAliases(@TempDir Path dir) {
        FileSecretStore store = store(dir);
        store.store("kiwoom-appkey", "a".toCharArray());
        store.store("kiwoom-appsecret", "b".toCharArray());

        assertEquals(Set.of("kiwoom-appkey", "kiwoom-appsecret"), store.aliases());
    }

    @Test
    @DisplayName("경로 구분자가 섞인 별칭은 거부한다")
    void rejectsPathTraversal(@TempDir Path dir) {
        FileSecretStore store = store(dir);

        assertThrows(IllegalArgumentException.class,
                () -> store.store("../../etc/passwd", "x".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> store.store("a/b", "x".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> store.load("..\\secret"));
    }

    @Test
    @DisplayName("빈 값은 저장하지 않는다")
    void rejectsEmptySecret(@TempDir Path dir) {
        FileSecretStore store = store(dir);

        assertThrows(IllegalArgumentException.class, () -> store.store("key", new char[0]));
        assertThrows(IllegalArgumentException.class, () -> store.store("key", null));
    }

    @Test
    @DisplayName("저장 후에도 임시 파일이 남지 않는다")
    void leavesNoTempFiles(@TempDir Path dir) throws Exception {
        FileSecretStore store = store(dir);
        store.store("key", "value".toCharArray());

        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    @DisplayName("char 배열과 byte 배열을 String 없이 변환한다")
    void convertsWithoutString() {
        char[] original = "비밀-secret-123".toCharArray();

        byte[] bytes = SecretBytes.toBytes(original);
        char[] roundTrip = SecretBytes.toChars(bytes);

        assertArrayEquals(original, roundTrip);
    }

    @Test
    @DisplayName("배열을 0으로 지운다")
    void wipesArrays() {
        char[] chars = "secret".toCharArray();
        byte[] bytes = {1, 2, 3};

        SecretBytes.wipe(chars);
        SecretBytes.wipe(bytes);

        assertArrayEquals(new char[]{0, 0, 0, 0, 0, 0}, chars);
        assertArrayEquals(new byte[]{0, 0, 0}, bytes);
    }

    @Test
    @DisplayName("암호화하지 않는 구현은 보호되지 않음을 스스로 알린다")
    void unprotectedCodecReportsItself(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir, new UnprotectedSecretCodec());

        assertFalse(store.isHardwareBacked());
        assertTrue(store.description().contains("개발용"));
    }

    @Test
    @DisplayName("운영체제에 맞는 저장소를 고른다")
    void factorySelectsByOs(@TempDir Path dir) {
        SecretStore store = SecretStoreFactory.create(dir);
        boolean onWindows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");

        assertEquals(onWindows, store.isHardwareBacked());

        store.store("key", "value".toCharArray());
        assertArrayEquals("value".toCharArray(), store.load("key").orElseThrow());
    }

    @Test
    @DisplayName("Windows 가 아니면 DPAPI 구현을 만들 수 없다")
    void dpapiRequiresWindows() {
        if (DpapiSecretCodec.isSupported()) {
            return;
        }
        assertThrows(SecretStoreException.class, DpapiSecretCodec::new);
    }

    @Test
    @DisplayName("복호화에 실패하면 오류를 낸다")
    void reportsDecryptFailure(@TempDir Path dir) throws Exception {
        SecretCodec failing = new SecretCodec() {
            @Override
            public byte[] encrypt(byte[] plaintext) {
                return plaintext.clone();
            }

            @Override
            public byte[] decrypt(byte[] ciphertext) {
                throw new SecretStoreException("복호화 실패");
            }

            @Override
            public boolean isHardwareBacked() {
                return true;
            }

            @Override
            public String description() {
                return "실패하는 구현";
            }
        };
        Files.writeString(dir.resolve("key.secret"), "손상된 데이터");

        SecretStore store = new FileSecretStore(dir, failing);

        assertThrows(SecretStoreException.class, () -> store.load("key"));
        assertTrue(Arrays.asList("key").contains("key"));
    }
}
