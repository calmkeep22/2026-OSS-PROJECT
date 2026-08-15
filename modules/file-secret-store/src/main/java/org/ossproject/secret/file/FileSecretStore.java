package org.ossproject.secret.file;

import org.ossproject.secret.SecretBytes;
import org.ossproject.secret.SecretProtectionLevel;
import org.ossproject.secret.SecretStore;
import org.ossproject.secret.SecretStoreException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Persists encrypted secret values as atomically replaced files. */
public final class FileSecretStore implements SecretStore {
    private static final Pattern ALLOWED_ALIAS = Pattern.compile("[a-zA-Z0-9._-]{1,64}");
    private static final String EXTENSION = ".secret";

    private final Path directory;
    private final SecretCodec codec;

    public FileSecretStore(Path directory, SecretCodec codec) {
        if (directory == null) throw new IllegalArgumentException("Storage directory is required.");
        if (codec == null) throw new IllegalArgumentException("Secret codec is required.");
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = codec;
        try {
            Files.createDirectories(this.directory);
        } catch (IOException error) {
            throw new SecretStoreException("Could not create the secret storage directory.", error);
        }
    }

    @Override
    public void store(String alias, char[] secret) {
        requireAlias(alias);
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("Secret value must not be empty.");
        }
        byte[] plaintext = null;
        byte[] ciphertext = null;
        Path temporary = null;
        try {
            plaintext = SecretBytes.toBytes(secret);
            ciphertext = codec.encrypt(plaintext);
            temporary = Files.createTempFile(directory, alias + "-", ".tmp");
            Files.write(temporary, ciphertext);
            moveReplacing(temporary, fileFor(alias));
            temporary = null;
        } catch (IOException error) {
            throw new SecretStoreException("Could not store secret alias: " + alias, error);
        } finally {
            SecretBytes.wipe(plaintext);
            SecretBytes.wipe(ciphertext);
            deleteQuietly(temporary);
        }
    }

    @Override
    public Optional<char[]> load(String alias) {
        requireAlias(alias);
        Path file = fileFor(alias);
        if (!Files.exists(file)) return Optional.empty();
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            ciphertext = Files.readAllBytes(file);
            plaintext = codec.decrypt(ciphertext);
            return Optional.of(SecretBytes.toChars(plaintext));
        } catch (IOException error) {
            throw new SecretStoreException("Could not load secret alias: " + alias, error);
        } finally {
            SecretBytes.wipe(ciphertext);
            SecretBytes.wipe(plaintext);
        }
    }

    @Override
    public void delete(String alias) {
        requireAlias(alias);
        try {
            Files.deleteIfExists(fileFor(alias));
        } catch (IOException error) {
            throw new SecretStoreException("Could not delete secret alias: " + alias, error);
        }
    }

    @Override
    public boolean contains(String alias) {
        requireAlias(alias);
        return Files.exists(fileFor(alias));
    }

    @Override
    public Set<String> aliases() {
        try (Stream<Path> files = Files.list(directory)) {
            Set<String> result = new LinkedHashSet<>();
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION))
                    .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                    .sorted()
                    .forEach(result::add);
            return Set.copyOf(result);
        } catch (IOException error) {
            throw new SecretStoreException("Could not list stored secret aliases.", error);
        }
    }

    @Override
    public SecretProtectionLevel protectionLevel() {
        return codec.protectionLevel();
    }

    @Override
    public String description() {
        return codec.description() + " · " + directory;
    }

    @Override
    public void close() {
        // File-backed implementation owns no open resources between calls.
    }

    private Path fileFor(String alias) {
        return directory.resolve(alias.toLowerCase(Locale.ROOT) + EXTENSION);
    }

    private static void requireAlias(String alias) {
        if (alias == null || !ALLOWED_ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Alias must contain 1-64 letters, numbers, dots, dashes, or underscores.");
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort cleanup of a temporary file.
        }
    }
}
