package org.ossproject.accessibility.notification;

public record SpeechOptions(double rate, int volume, String voiceName) {
    public static final SpeechOptions DEFAULT = new SpeechOptions(1.0, 100, null);

    public SpeechOptions {
        if (rate < 0.5 || rate > 2.0) {
            throw new IllegalArgumentException("rate must be between 0.5 and 2.0: " + rate);
        }
        if (volume < 0 || volume > 100) {
            throw new IllegalArgumentException("volume must be between 0 and 100: " + volume);
        }
        if (voiceName != null && voiceName.isBlank()) voiceName = null;
    }

    public SpeechOptions withRate(double rate) { return new SpeechOptions(rate, volume, voiceName); }
    public SpeechOptions withVolume(int volume) { return new SpeechOptions(rate, volume, voiceName); }
    public SpeechOptions withVoiceName(String voiceName) { return new SpeechOptions(rate, volume, voiceName); }
}
