package org.ossproject.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * {@code char[]} 와 {@code byte[]} 를 {@code String} 을 거치지 않고 변환한다.
 *
 * <p>중간에 {@code String} 을 만들면 그 순간 비밀 값이 힙에 불변으로 남는다. 변환에 쓴
 * 임시 버퍼는 모두 0으로 덮어쓴다.
 */
public final class SecretBytes {

    private SecretBytes() {
    }

    /** UTF-8 로 인코딩한다. 중간 버퍼는 지운다. */
    public static byte[] toBytes(char[] chars) {
        if (chars == null) {
            throw new IllegalArgumentException("입력 값은 필수입니다.");
        }
        CharBuffer charBuffer = CharBuffer.wrap(chars);
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        clear(byteBuffer);
        return bytes;
    }

    /** UTF-8 로 디코딩한다. 중간 버퍼는 지운다. */
    public static char[] toChars(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("입력 값은 필수입니다.");
        }
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        CharBuffer charBuffer = StandardCharsets.UTF_8.decode(byteBuffer);
        char[] chars = new char[charBuffer.remaining()];
        charBuffer.get(chars);
        clear(charBuffer);
        return chars;
    }

    public static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    /** 버퍼가 배열을 직접 감싸고 있을 때만 지울 수 있다. */
    private static void clear(ByteBuffer buffer) {
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
    }

    private static void clear(CharBuffer buffer) {
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), '\0');
        }
    }
}
