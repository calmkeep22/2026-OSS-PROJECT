package org.ossproject.accessibility.notification;

public interface SpeechListener {
    default void onStarted(SpeechRequest request) {}
    default void onCompleted(SpeechRequest request) {}
    default void onInterrupted(SpeechRequest request) {}
    default void onFailed(SpeechRequest request, RuntimeException error) {}
}
