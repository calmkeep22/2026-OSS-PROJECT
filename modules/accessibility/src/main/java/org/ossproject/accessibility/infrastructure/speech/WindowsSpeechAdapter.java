package org.ossproject.accessibility.infrastructure.speech;

import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechVoice;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;

public final class WindowsSpeechAdapter implements SpeechPort, SpeechVoiceProvider {
    private final Object lock = new Object();
    private Process activeProcess;
    private volatile SpeechOptions options = SpeechOptions.DEFAULT;
    private volatile boolean stopRequested;

    @Override public void applyOptions(SpeechOptions options) { this.options = options; }

    @Override public void speak(String text) throws InterruptedException {
        SpeechOptions current = options;
        Process process;
        try {
            stopRequested = false;
            process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encodedCommand(current, text))
                    .redirectErrorStream(true)
                    .start();
            synchronized (lock) { activeProcess = process; }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 && !stopRequested) {
                String detail = output.isBlank() ? "오류 출력 없음" : output;
                throw new SpeechSynthesisException("Windows TTS 실행 실패 (종료 코드 " + exitCode + "): " + detail);
            }
        } catch (IOException error) {
            throw new SpeechSynthesisException("Windows TTS 프로세스를 시작하지 못했습니다.", error);
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

    static String encodedCommand(SpeechOptions options, String text) {
        String encodedText = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String encodedVoice = Base64.getEncoder().encodeToString(
                (options.voiceName() == null ? "" : options.voiceName()).getBytes(StandardCharsets.UTF_8));
        String script = "$ErrorActionPreference='Stop'; "
                + "Add-Type -AssemblyName System.Speech; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; "
                + "try { "
                + "$s.Rate=" + toWindowsRate(options.rate()) + "; "
                + "$s.Volume=" + options.volume() + "; "
                + "$t=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + encodedText + "')); "
                + "$v=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + encodedVoice + "')); "
                + "if ($v) { try { $s.SelectVoice($v) } catch {} }; "
                + "$s.Speak($t) "
                + "} finally { $s.Dispose() }";
        return encodeScript(script);
    }

    @Override public List<SpeechVoice> availableVoices() {
        String script = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Speech; "
                + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; try { "
                + "$s.GetInstalledVoices() | ForEach-Object { "
                + "$n=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.VoiceInfo.Name)); "
                + "$c=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_.VoiceInfo.Culture.Name)); "
                + "Write-Output ($n+'|'+$c) } } finally { $s.Dispose() }";
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encodeScript(script)).redirectErrorStream(true).start();
            List<String> lines = process.inputReader(StandardCharsets.UTF_8).lines().toList();
            int exitCode = process.waitFor();
            if (exitCode != 0) return List.of();
            List<SpeechVoice> voices = new ArrayList<>();
            for (String line : lines) {
                String[] parts = line.trim().split("\\|", -1);
                if (parts.length != 2) continue;
                try {
                    String name = decodeUtf8(parts[0]);
                    voices.add(new SpeechVoice(name, name, decodeUtf8(parts[1])));
                } catch (IllegalArgumentException malformedOutput) {
                    // Ignore a non-voice line from PowerShell and keep valid entries.
                }
            }
            return List.copyOf(voices);
        } catch (IOException error) {
            return List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static String encodeScript(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String decodeUtf8(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    // System.Speech.Rate spans -10..10 on a roughly logarithmic scale; our 0.5x-2.0x
    // multiplier maps onto it via log2 so 1.0x lands exactly on the neutral rate 0.
    private static int toWindowsRate(double rate) {
        double clamped = Math.max(0.5, Math.min(2.0, rate));
        return (int) Math.round(10 * (Math.log(clamped) / Math.log(2)));
    }
}
