package org.ossproject.accessibility.port;

import org.ossproject.accessibility.notification.SpeechOptions;

public interface SpeechPort extends AutoCloseable {
    void speak(String text) throws InterruptedException;
    void stop();
    default void applyOptions(SpeechOptions options) {}
    @Override default void close() { stop(); }
}
