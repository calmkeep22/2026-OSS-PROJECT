package org.ossproject.accessibility.infrastructure.speech;

import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechVoice;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MacSpeechAdapter implements SpeechPort, SpeechVoiceProvider {
    private final Object lock = new Object();
    private Process activeProcess;
    private volatile SpeechOptions options = SpeechOptions.DEFAULT;
    private volatile boolean stopRequested;

    @Override public void applyOptions(SpeechOptions options) { this.options = options; }

    @Override public void speak(String text) throws InterruptedException {
        SpeechOptions current = options;
        stopRequested = false;
        List<String> command = new ArrayList<>();
        command.add("say");
        command.add("-r");
        command.add(String.valueOf(toWordsPerMinute(current.rate())));
        if (current.voiceName() != null) {
            command.add("-v");
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
                throw new SpeechSynthesisException("macOS TTS 실행 실패: " + output);
            }
        } catch (IOException error) {
            throw new SpeechSynthesisException("macOS say 프로세스를 시작하지 못했습니다.", error);
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
        try {
            Process process = new ProcessBuilder("say", "-v", "?").redirectErrorStream(true).start();
            List<SpeechVoice> voices = process.inputReader().lines()
                    .map(String::trim).filter(line -> !line.isEmpty())
                    .map(line -> line.split("\\s+", 3))
                    .filter(parts -> parts.length >= 2)
                    .map(parts -> new SpeechVoice(parts[0], parts[0], parts[1]))
                    .toList();
            return process.waitFor() == 0 ? voices : List.of();
        } catch (IOException error) {
            return List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // `say -r` takes words-per-minute directly; 180 wpm is Apple's own default rate,
    // so our 1.0x lands exactly on it. `say` has no per-utterance volume flag.
    static int toWordsPerMinute(double rate) {
        double clamped = Math.max(0.5, Math.min(2.0, rate));
        return (int) Math.round(180 * clamped);
    }
}
