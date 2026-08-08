package org.ossproject.accessibility.notification;

public record SpeechQueueConfig(int maxPendingRequests) {
    public static final SpeechQueueConfig DEFAULT = new SpeechQueueConfig(100);

    public SpeechQueueConfig {
        if (maxPendingRequests < 1) {
            throw new IllegalArgumentException("maxPendingRequests must be at least 1");
        }
    }
}
