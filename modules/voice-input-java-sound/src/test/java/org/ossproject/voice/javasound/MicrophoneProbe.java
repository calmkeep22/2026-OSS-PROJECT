package org.ossproject.voice.javasound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 이 컴퓨터의 마이크를 실제로 열어 보는 확인. 평소에는 돌지 않는다.
 *
 * <p>{@link UtteranceDetector} 검사는 마이크 없이 판단만 본다. 그것이 다 통과해도
 * 마이크에서 소리가 안 들어오면 아무 일도 일어나지 않는다. 그 둘은 다른 문제이고,
 * 뒤엣것은 사람이 마이크에 대고 말해 봐야만 드러난다.
 *
 * <pre>
 *   ./gradlew :modules:voice-input-java-sound:test --tests "*MicrophoneProbe" "-Dvoice.mic=1"
 *   ./gradlew :modules:voice-input-java-sound:test --tests "*MicrophoneProbe" "-Dvoice.mic=record" --rerun-tasks
 * </pre>
 *
 * <p>{@code record} 로 주면 장치마다 실제로 녹음해 <b>WAV 파일로 남긴다.</b> 숫자만
 * 보면 소리가 들어왔는지까지만 알고 무엇이 들어왔는지는 모른다. 앱이 자기 목소리를
 * 녹음하고 있던 것도 결국 소리를 들어 봐야 알 수 있는 종류였다.
 */
@EnabledIfSystemProperty(named = "voice.mic", matches = ".+")
class MicrophoneProbe {

    private static final AudioFormat FORMAT = MicrophoneCapture.FORMAT;
    private static final int CHUNK_BYTES =
            (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() / 20);   // 50밀리초
    private static final int SECONDS = 5;
    private static final Path OUT = Path.of("build", "mic-check");

    @Test
    @DisplayName("이 컴퓨터에서 마이크를 열 수 있는지")
    void reportsWhatThisMachineHas() {
        DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);
        System.out.println();
        System.out.println("우리가 쓰는 형식: " + FORMAT);
        System.out.println("이 형식을 받는 입력 장치:");
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(wanted)) {
                System.out.println("  - " + info.getName());
            }
        }
        MicrophoneCapture microphone = new MicrophoneCapture();
        System.out.println("쓸 수 있음: " + microphone.available());
        if (!microphone.available()) System.out.println("사유: " + microphone.unavailableReason());
        microphone.close();
    }

    /**
     * 장치마다 실제로 녹음해 파일로 남긴다.
     *
     * <p>말할 때를 맞춰 달라고 하지 않는다. 이 검사는 빌드 로그에 섞여 나와 안내를
     * 제때 볼 수 없다. 장치마다 5초씩 도는 동안 계속 말하면 된다.
     */
    @Test
    @EnabledIfSystemProperty(named = "voice.mic", matches = "record")
    @DisplayName("장치마다 녹음해 WAV 로 남긴다 (도는 동안 계속 말하면 된다)")
    void recordsFromEveryDevice() throws Exception {
        Files.createDirectories(OUT);
        DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);

        System.out.println();
        System.out.println("======== 장치별 녹음 ========");
        System.out.println("앱이 기본으로 여는 장치: " + defaultDeviceName());
        System.out.println();

        int devices = 0;
        double best = 0.0;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            if (!mixer.isLineSupported(wanted)) continue;

            devices++;
            String name = String.format("%02d-%s", devices, safe(info.getName()));
            try {
                byte[] pcm = record(mixer, wanted);
                double peak = peak(pcm);
                best = Math.max(best, peak);
                Path saved = OUT.resolve(name + ".wav");
                Files.write(saved, MicrophoneCapture.toWav(pcm));
                System.out.printf("  %-38s 최대 %.4f%n", info.getName(), peak);
                System.out.println("      -> " + saved.toAbsolutePath());
            } catch (Exception failure) {
                System.out.printf("  %-38s 열지 못함: %s%n", info.getName(), failure.getMessage());
            }
        }
        System.out.println();
        System.out.println("  위 WAV 를 재생해 보면 무엇이 녹음됐는지 알 수 있습니다.");
        System.out.println("  최대값이 0.01 을 넘는 장치가 있으면 그 장치를 쓰면 됩니다.");
        System.out.println("=============================");
        System.out.println();

        assertTrue(devices > 0, "이 형식을 받는 입력 장치가 하나도 없습니다.");
        assertTrue(best > 0.0, "어느 장치에서도 소리가 전혀 들어오지 않았습니다."
                + " 윈도우 소리 설정에서 입력 장치와 음소거를 확인해주세요.");
    }

    private static byte[] record(Mixer mixer, DataLine.Info wanted) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (TargetDataLine line = (TargetDataLine) mixer.getLine(wanted)) {
            line.open(FORMAT, CHUNK_BYTES * 8);
            line.start();
            byte[] buffer = new byte[CHUNK_BYTES];
            for (int chunk = 0; chunk < SECONDS * 20; chunk++) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) captured.write(buffer, 0, read);
            }
            line.stop();
        }
        return captured.toByteArray();
    }

    private static double peak(byte[] pcm) {
        double loudest = 0.0;
        byte[] slice = new byte[CHUNK_BYTES];
        for (int at = 0; at + CHUNK_BYTES <= pcm.length; at += CHUNK_BYTES) {
            System.arraycopy(pcm, at, slice, 0, CHUNK_BYTES);
            loudest = Math.max(loudest, MicrophoneCapture.loudness(slice, CHUNK_BYTES));
        }
        return loudest;
    }

    /** 앱이 실제로 여는 장치. 이것이 진짜 마이크가 아니면 소리가 안 들어온다. */
    private static String defaultDeviceName() {
        try (TargetDataLine line = AudioSystem.getTargetDataLine(FORMAT)) {
            return String.valueOf(line.getLineInfo());
        } catch (Exception unavailable) {
            return "확인 실패: " + unavailable.getMessage();
        }
    }

    private static String safe(String name) {
        StringBuilder cleaned = new StringBuilder();
        for (char letter : name.toCharArray()) {
            cleaned.append(Character.isLetterOrDigit(letter) ? letter : '_');
        }
        return cleaned.toString();
    }

    /**
     * 녹음하면서 스피커로 소리를 내본다.
     *
     * <p>앞뒤가 맞지 않는 일이 있었다. 마이크는 완전 무음(진폭 ±1)인데, 앱이 스피커로
     * 낸 말은 녹음돼 인식까지 됐다. 마이크가 죽었다면 그럴 수 없다.
     *
     * <p>둘 중 하나다. 마이크가 그때는 살아 있었거나, 아니면 녹음되는 것이 마이크가
     * 아니라 스피커로 나가는 소리(되돌림)거나. 이 검사가 그것을 가른다 — 사람이 아무
     * 말도 하지 않고, 소리는 스피커로만 낸다.
     */
    @Test
    @EnabledIfSystemProperty(named = "voice.mic", matches = "record")
    @DisplayName("녹음 중 스피커로 소리를 내 되돌아 들어오는지 본다 (말하지 마세요)")
    void checksWhetherOutputLoopsBack() throws Exception {
        Files.createDirectories(OUT);
        DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);

        System.out.println();
        System.out.println("======== 되돌림 확인 (말하지 마세요) ========");

        try (TargetDataLine line = AudioSystem.getTargetDataLine(FORMAT)) {
            line.open(FORMAT, CHUNK_BYTES * 8);
            line.start();

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            byte[] buffer = new byte[CHUNK_BYTES];

            // 1초 조용히
            for (int chunk = 0; chunk < 20; chunk++) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) captured.write(buffer, 0, read);
            }
            double quiet = peak(captured.toByteArray());

            // 스피커로 소리를 내면서 2초 더 녹음
            Thread speaker = new Thread(MicrophoneProbe::playTone, "tone");
            speaker.setDaemon(true);
            speaker.start();

            ByteArrayOutputStream during = new ByteArrayOutputStream();
            for (int chunk = 0; chunk < 40; chunk++) {
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) during.write(buffer, 0, read);
            }
            line.stop();
            speaker.join(3000);

            double loud = peak(during.toByteArray());
            Files.write(OUT.resolve("loopback.wav"), MicrophoneCapture.toWav(during.toByteArray()));

            System.out.printf("  스피커 끄고 있을 때  %.4f%n", quiet);
            System.out.printf("  스피커 소리 낼 때    %.4f%n", loud);
            System.out.println(loud > quiet * 3 && loud > 0.005
                    ? "  판정: 스피커 소리가 녹음에 들어옵니다. 마이크가 아니라 되돌림입니다."
                    : "  판정: 스피커 소리도 안 들어옵니다. 녹음 경로 자체가 무음입니다.");
            System.out.println("  -> " + OUT.resolve("loopback.wav").toAbsolutePath());
        }
        System.out.println("==========================================");
        System.out.println();
    }

    /** 스피커로 2초 동안 440Hz 를 낸다. 사람 목소리와 헷갈리지 않는 순음이다. */
    private static void playTone() {
        AudioFormat out = new AudioFormat(16_000f, 16, 1, true, false);
        byte[] tone = new byte[16_000 * 2 * 2];
        for (int frame = 0; frame < tone.length / 2; frame++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * frame / 16_000.0) * 12_000);
            tone[frame * 2] = (byte) (value & 0xff);
            tone[frame * 2 + 1] = (byte) ((value >> 8) & 0xff);
        }
        try (javax.sound.sampled.SourceDataLine speaker = AudioSystem.getSourceDataLine(out)) {
            speaker.open(out);
            speaker.start();
            speaker.write(tone, 0, tone.length);
            speaker.drain();
        } catch (Exception silent) {
            System.out.println("  (스피커로 소리를 내지 못했습니다: " + silent.getMessage() + ")");
        }
    }
}
