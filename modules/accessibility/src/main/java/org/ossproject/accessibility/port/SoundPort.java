package org.ossproject.accessibility.port;

import org.ossproject.accessibility.notification.SoundCue;

public interface SoundPort extends AutoCloseable {
    void play(SoundCue cue);
    void stop();
    void setVolume(double volume);
    @Override default void close() { stop(); }
}
