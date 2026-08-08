package org.ossproject.accessibility.infrastructure.speech;

public final class SpeechSynthesisException extends RuntimeException {
    public SpeechSynthesisException(String message) { super(message); }
    public SpeechSynthesisException(String message, Throwable cause) { super(message, cause); }
}
