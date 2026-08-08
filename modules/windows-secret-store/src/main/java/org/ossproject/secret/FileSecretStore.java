package org.ossproject.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 암호화된 비밀 값을 파일로 보관한다.
 *
 * <p>암호화 자체는 {@link SecretCodec} 이 담당하고, 이 클래스는 파일 이름과 쓰기 안전성만
 * 책임진다. 저장은 임시 파일에 쓴 뒤 원자적으로 옮기므로, 저장 도중 프로그램이 죽어도
 * 기존 값이 반쯤 덮여 못 쓰게 되는 일은 없다.
 *
 * <p>별칭은 파일 이름이 되므로 문자를 제한한다. 경로 구분자나 상위 디렉터리 참조가 섞여
 * 엉뚱한 위치에 쓰이는 것을 막기 위해서다.
 */
public final class FileSecretStore implements SecretStore {

    private static final Pattern ALLOWED_ALIAS = Pattern.compile("[a-zA-Z0-9._-]{1,64}");
    private static final String EXTENSION = ".secret";

    private final Path directory;
    private final SecretCodec codec;

    public FileSecretStore(Path directory, SecretCodec codec) {
        if (directory == null) {
            throw new IllegalArgumentException("저장 경로는 필수입니다.");
        }
        if (codec == null) {
            throw new IllegalArgumentException("암호화 구현은 필수입니다.");
        }
        this.directory = directory;
        this.codec = codec;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new SecretStoreException("저장 경로를 만들지 못했습니다. " + directory, e);
        }
    }

    @Override
    public void store(String alias, char[] secret) {
        requireAlias(alias);
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("비밀 값은 비어 있을 수 없습니다.");
        }

        byte[] plaintext = null;
        byte[] ciphertext = null;
        Path temporary = null;
        try {
            plaintext = SecretBytes.toBytes(secret);
            ciphertext = codec.encrypt(plaintext);

            temporary = Files.createTempFile(directory, alias, ".tmp");
            Files.write(temporary, ciphertext);
            Files.move(temporary, fileFor(alias),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
        } catch (IOException e) {
            throw new SecretStoreException("비밀 값 " + alias + " 을(를) 저장하지 못했습니다.", e);
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
        if (!Files.exists(file)) {
            return Optional.empty();
        }

        byte[] ciphertext = null;
        byte[] plaintext = null;
        try {
            ciphertext = Files.readAllBytes(file);
            plaintext = codec.decrypt(ciphertext);
            return Optional.of(SecretBytes.toChars(plaintext));
        } catch (IOException e) {
            throw new SecretStoreException("비밀 값 " + alias + " 을(를) 읽지 못했습니다.", e);
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
        } catch (IOException e) {
            throw new SecretStoreException("비밀 값 " + alias + " 을(를) 지우지 못했습니다.", e);
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
            Set<String> aliases = new LinkedHashSet<>();
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION))
                    .map(name -> name.substring(0, name.length() - EXTENSION.length()))
                    .sorted()
                    .forEach(aliases::add);
            return Set.copyOf(aliases);
        } catch (IOException e) {
            throw new SecretStoreException("저장된 비밀 목록을 읽지 못했습니다.", e);
        } catch (UncheckedIOException e) {
            throw new SecretStoreException("저장된 비밀 목록을 읽지 못했습니다.", e);
        }
    }

    @Override
    public boolean isHardwareBacked() {
        return codec.isHardwareBacked();
    }

    @Override
    public String description() {
        return codec.description() + " · 저장 위치 " + directory;
    }

    @Override
    public void close() {
        // 파일 기반이라 열어 둔 자원이 없다.
    }

    private Path fileFor(String alias) {
        return directory.resolve(alias.toLowerCase(Locale.ROOT) + EXTENSION);
    }

    private static void requireAlias(String alias) {
        if (alias == null || !ALLOWED_ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException(
                    "별칭은 영문, 숫자, 점, 밑줄, 붙임표만 쓸 수 있고 64자 이하여야 합니다. 입력값 " + alias);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일 정리 실패는 원래 오류를 덮지 않는다.
        }
    }
}
