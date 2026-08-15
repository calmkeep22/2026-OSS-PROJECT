package org.ossproject.secret;

import java.util.Optional;
import java.util.Set;

/** Platform-independent boundary for storing sensitive credentials. */
public interface SecretStore extends AutoCloseable {
    void store(String alias, char[] secret);

    Optional<char[]> load(String alias);

    void delete(String alias);

    boolean contains(String alias);

    Set<String> aliases();

    /** The strongest protection level guaranteed by this implementation. */
    SecretProtectionLevel protectionLevel();

    default boolean isAvailable() {
        return protectionLevel().isAvailable();
    }

    /** A safe, user-facing description that never contains credential values. */
    String description();

    @Override
    void close();
}
