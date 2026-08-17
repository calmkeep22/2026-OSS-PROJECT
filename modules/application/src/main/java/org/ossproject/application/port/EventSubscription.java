package org.ossproject.application.port;

/**
 * 화면이나 기능의 수명에 맞춰 이벤트 구독을 해제하는 핸들.
 *
 * <p>{@link #close()}는 여러 번 호출해도 안전해야 한다.
 */
@FunctionalInterface
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}
