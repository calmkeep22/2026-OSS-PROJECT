package org.ossproject.secret;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Converts and clears mutable secret buffers without creating an intermediate String. */
public final class SecretBytes {
    private SecretBytes() {
    }

    public static byte[] toBytes(char[] chars) {
        if (chars == null) {
            throw new IllegalArgumentException("Secret value is required.");
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        clear(encoded);
        return bytes;
    }

    public static char[] toChars(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Secret value is required.");
        }
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] chars = new char[decoded.remaining()];
        decoded.get(chars);
        clear(decoded);
        return chars;
    }

    public static void wipe(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    public static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static void clear(ByteBuffer buffer) {
        if (buffer.hasArray()) Arrays.fill(buffer.array(), (byte) 0);
    }

    private static void clear(CharBuffer buffer) {
        if (buffer.hasArray()) Arrays.fill(buffer.array(), '\0');
    }
}
