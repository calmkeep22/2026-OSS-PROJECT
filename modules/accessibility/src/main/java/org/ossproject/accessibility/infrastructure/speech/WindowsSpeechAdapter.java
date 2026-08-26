package org.ossproject.accessibility.infrastructure.speech;

import org.ossproject.accessibility.notification.SpeechOptions;
import org.ossproject.accessibility.notification.SpeechVoice;
import org.ossproject.accessibility.port.SpeechPort;
import org.ossproject.accessibility.port.SpeechVoiceProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Windows 음성 합성.
 *
 * <p>PowerShell 을 <b>한 번만 띄워 두고 계속 쓴다.</b> 예전에는 문장마다 새로 띄웠는데,
 * 실측에서 한 문장에 0.97초가 들었고 그중 0.45초가 프로세스 기동, 나머지가
 * {@code System.Speech} 적재였다. 안내가 연달아 나오면 그만큼씩 밀려, 화면을 볼 수 없는
 * 사용자는 이미 지나간 화면의 설명을 듣게 된다.
 *
 * <p>상주 프로세스는 말하는 동안 표준입력을 읽지 못한다. 그래서 {@link #stop()} 은
 * 취소 명령을 보내는 대신 프로세스를 죽이고, 다음 발화에서 다시 띄운다. 중단은 드물게
 * 일어나므로 그때만 기동 비용을 치르면 된다.
 *
 * <p>주고받는 말은 한 줄씩이다. 자바가 {@code SPEAK <글자> <속도> <크기> <목소리>} 를
 * 보내면 프로세스가 다 읽어 준 뒤 {@code OSA-DONE} 을 돌려준다. 값은 Base64 로 감싼다 —
 * 종목명에 칸이나 따옴표가 들어가면 명령이 쪼개진다.
 */
public final class WindowsSpeechAdapter implements SpeechPort, SpeechVoiceProvider {

    private static final String READY = "OSA-READY";
    private static final String DONE = "OSA-DONE";
    private static final String ERROR = "OSA-ERR";
    /** 빈 값을 그대로 보내면 명령을 칸으로 쪼갤 때 자리가 밀린다. */
    private static final String NONE = "-";

    private final Object lock = new Object();
    private Process resident;
    private BufferedWriter toProcess;
    private BufferedReader fromProcess;
    private volatile SpeechOptions options = SpeechOptions.DEFAULT;
    private volatile boolean stopRequested;

    public WindowsSpeechAdapter() {
        // 앱이 어떻게 끝나든 상주 프로세스를 남기지 않는다. close() 를 부르는 길에만
        // 기대면 강제 종료나 예외로 끝났을 때 powershell.exe 가 계속 떠 있는다.
        Runtime.getRuntime().addShutdownHook(new Thread(this::discardResident, "tts-shutdown"));
    }

    @Override public void applyOptions(SpeechOptions options) { this.options = options; }

    @Override public void speak(String text) throws InterruptedException {
        stopRequested = false;
        try {
            speakOnce(text);
        } catch (IOException firstTry) {
            // 상주 프로세스가 조용히 죽었을 수 있다. 한 번만 다시 띄워 시도한다.
            // 여기서 포기하면 그 뒤로 앱이 영영 말을 하지 않는다.
            discardResident();
            if (stopRequested) return;
            try {
                speakOnce(text);
            } catch (IOException giveUp) {
                throw new SpeechSynthesisException("Windows TTS 프로세스를 쓰지 못했습니다.", giveUp);
            }
        }
    }

    private void speakOnce(String text) throws IOException, InterruptedException {
        SpeechOptions current = options;
        BufferedWriter writer;
        BufferedReader reader;
        synchronized (lock) {
            ensureResident();
            writer = toProcess;
            reader = fromProcess;
        }
        writer.write(command(current, text));
        writer.newLine();
        writer.flush();

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals(DONE)) return;
            if (line.startsWith(ERROR)) {
                throw new SpeechSynthesisException(
                        "Windows TTS 실패: " + line.substring(ERROR.length()).trim());
            }
            // 그 밖의 줄은 PowerShell 이 흘린 것이다. 무시하고 표시를 계속 기다린다.
        }
        // 표시 없이 끊겼다 — 중단이거나 프로세스가 죽은 것이다.
        if (stopRequested) return;
        throw new IOException("상주 TTS 프로세스가 응답 없이 끊겼습니다.");
    }

    /** 자물쇠를 쥔 채로 부른다. */
    private void ensureResident() throws IOException {
        if (resident != null && resident.isAlive()) return;
        Process started = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encodeScript(residentScript()))
                .redirectErrorStream(true)
                .start();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null && !line.equals(READY)) {
            // 준비됐다는 표시가 올 때까지 기다린다.
        }
        if (line == null) {
            started.destroyForcibly();
            throw new IOException("상주 TTS 프로세스가 준비되지 못했습니다.");
        }
        resident = started;
        toProcess = writer;
        fromProcess = reader;
    }

    private void discardResident() {
        synchronized (lock) {
            if (resident != null) resident.destroyForcibly();
            resident = null;
            toProcess = null;
            fromProcess = null;
        }
    }

    @Override public void stop() {
        stopRequested = true;
        // 말하는 동안에는 표준입력을 읽지 못하므로 취소 명령이 닿지 않는다. 죽이고
        // 다음 발화에서 다시 띄운다.
        discardResident();
    }

    /** 한 줄짜리 발화 명령. 값은 칸으로 나뉘므로 글자와 목소리는 Base64 로 감싼다. */
    static String command(SpeechOptions options, String text) {
        String voice = options.voiceName() == null || options.voiceName().isBlank()
                ? NONE
                : Base64.getEncoder().encodeToString(
                        options.voiceName().getBytes(StandardCharsets.UTF_8));
        return "SPEAK "
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8))
                + " " + toWindowsRate(options.rate())
                + " " + options.volume()
                + " " + voice;
    }

    private static String residentScript() {
        // 괄호를 둘 연다. 붙여 쓸 때 닫는 괄호도 둘이어야 한다 —
        // 하나만 닫았다가 PowerShell 이 통째로 구문 오류를 냈다.
        String decode = "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String";
        return String.join(" ",
                "$ErrorActionPreference='Stop';",
                "Add-Type -AssemblyName System.Speech;",
                "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;",
                "[Console]::OutputEncoding=[Text.Encoding]::UTF8;",
                "Write-Output '" + READY + "';",
                "while ($true) {",
                "  $line=[Console]::In.ReadLine();",
                "  if ($null -eq $line) { break };",
                "  if ($line -eq 'QUIT') { break };",
                "  $p=$line.Split(' ');",
                "  if ($p.Length -lt 5) { continue };",
                "  if ($p[0] -ne 'SPEAK') { continue };",
                "  try {",
                "    $s.Rate=[int]$p[2];",
                "    $s.Volume=[int]$p[3];",
                "    if ($p[4] -ne '" + NONE + "') { try { $s.SelectVoice(" + decode + "($p[4]))) } catch {} };",
                "    $s.Speak(" + decode + "($p[1])));",
                "    Write-Output '" + DONE + "'",
                "  } catch {",
                "    Write-Output ('" + ERROR + " ' + $_.Exception.Message)",
                "  }",
                "};",
                "$s.Dispose()");
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
