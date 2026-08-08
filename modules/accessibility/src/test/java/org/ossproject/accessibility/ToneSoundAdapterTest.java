package org.ossproject.accessibility;

import org.junit.jupiter.api.Test;
import org.ossproject.accessibility.infrastructure.sound.ToneSoundAdapter;
import org.ossproject.accessibility.notification.SoundCue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToneSoundAdapterTest {
    @Test void validatesVolumeAndCloseIsIdempotent() {
        ToneSoundAdapter adapter = new ToneSoundAdapter();
        adapter.setVolume(0.5);
        assertThrows(IllegalArgumentException.class, () -> adapter.setVolume(-0.1));
        assertThrows(IllegalArgumentException.class, () -> adapter.setVolume(1.1));
        adapter.close();
        assertDoesNotThrow(adapter::close);
        assertThrows(IllegalStateException.class, () -> adapter.play(SoundCue.SUCCESS));
        assertThrows(IllegalStateException.class, () -> adapter.setVolume(0.5));
    }
}
