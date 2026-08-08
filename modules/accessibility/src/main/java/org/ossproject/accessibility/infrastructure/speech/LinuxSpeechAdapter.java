package org.ossproject.accessibility.infrastructure.speech;

import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechVoice;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class LinuxSpeechAdapter implements SpeechPort, SpeechVoiceProvider {
    private final Object lock = new Object();
    private Process activeProcess;
    private volatile SpeechOptions options = SpeechOptions.DEFAULT;
    private volatile boolean stopRequested;

    @Override public void applyOptions(SpeechOptions options) { this.options = options; }

    @Override public void speak(String text) throws InterruptedException {
        SpeechOptions current = options;
        stopRequested = false;
        List<String> command = new ArrayList<>();
        command.add("spd-say");
        command.add("-w");
        command.add("-r");
        command.add(String.valueOf(toSpdRate(current.rate())));
        command.add("-i");
        command.add(String.valueOf(toSpdVolume(current.volume())));
        if (current.voiceName() != null) {
            command.add("-y");
            command.add(current.voiceName());
        }
        command.add(text);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            synchronized (lock) { activeProcess = process; }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 && !stopRequested) {
                throw new SpeechSynthesisException("Linux TTS 실행 실패: " + output);
            }
        } catch (IOException error) {
            throw new SpeechSynthesisException("Linux spd-say 프로세스를 시작하지 못했습니다.", error);
        } finally {
            synchronized (lock) { activeProcess = null; }
        }
    }

    @Override public void stop() {
        stopRequested = true;
        synchronized (lock) {
            if (activeProcess != null && activeProcess.isAlive()) activeProcess.destroyForcibly();
        }
    }

    @Override public List<SpeechVoice> availableVoices() {
        return List.of();
    }

    // speech-dispatcher's rate is a relative -100..100 scale (0 = default); reuse the
    // same log2 mapping as Windows so 1.0x lands on 0.
    static int toSpdRate(double rate) {
        double clamped = Math.max(0.5, Math.min(2.0, rate));
        return (int) Math.round(100 * (Math.log(clamped) / Math.log(2)));
    }

    // Our volume is an absolute 0-100 "loudness" scale (100 = full); spd-say wants
    // -100..100, so we stretch our range onto it linearly.
    static int toSpdVolume(int volume) {
        return Math.round(volume * 2f - 100);
    }
}
