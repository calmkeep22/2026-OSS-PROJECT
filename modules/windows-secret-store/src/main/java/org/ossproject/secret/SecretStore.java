package org.ossproject.secret;

import java.util.Optional;
import java.util.Set;

/**
 * API 키 같은 비밀 값을 보관한다.
 *
 * <p>값은 {@code char[]} 로 주고받는다. {@code String} 은 불변이라 GC 되기 전까지 힙에 남고
 * 힙 덤프에 그대로 찍히기 때문이다. 호출자는 다 쓴 배열을
 * {@link SecretBytes#wipe(char[])} 로 지워야 한다.
 */
public interface SecretStore extends AutoCloseable {

    /** 저장한다. 같은 별칭이 이미 있으면 덮어쓴다. */
    void store(String alias, char[] secret);

    /** 읽는다. 없으면 비어 있는 값. 반환된 배열은 호출자가 지워야 한다. */
    Optional<char[]> load(String alias);

    void delete(String alias);

    boolean contains(String alias);

    Set<String> aliases();

    /**
     * 이 저장소가 운영체제의 보안 기능으로 실제 암호화하는지 여부.
     *
     * <p>거짓이면 개발용 대체 구현이라는 뜻이므로, 화면 계층은 사용자에게 실제 API 키를
     * 넣지 말라고 경고해야 한다.
     */
    boolean isHardwareBacked();

    /** 사람이 읽을 수 있는 저장소 설명. 설정 화면에 그대로 보여 준다. */
    String description();

    @Override
    void close();
}
