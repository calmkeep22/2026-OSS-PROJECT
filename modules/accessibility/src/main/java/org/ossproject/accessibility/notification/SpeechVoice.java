package org.ossproject.accessibility.notification;

import java.util.Objects;

public record SpeechVoice(String id, String displayName, String language) {
    public SpeechVoice {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        language = language == null ? "" : language;
    }
}
