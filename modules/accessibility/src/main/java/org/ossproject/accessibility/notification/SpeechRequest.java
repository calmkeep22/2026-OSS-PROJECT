package org.ossproject.accessibility.notification;

import java.util.Objects;

public record SpeechRequest(String text, SpeechPriority priority, String deduplicationKey,
                            SpeechMergePolicy mergePolicy) {
    public SpeechRequest(String text, SpeechPriority priority, String deduplicationKey) {
        this(text, priority, deduplicationKey, SpeechMergePolicy.KEEP_FIRST);
    }

    public SpeechRequest {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("음성 안내 내용은 필수입니다.");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(mergePolicy, "mergePolicy");
        deduplicationKey = deduplicationKey == null || deduplicationKey.isBlank() ? text : deduplicationKey;
    }
}
