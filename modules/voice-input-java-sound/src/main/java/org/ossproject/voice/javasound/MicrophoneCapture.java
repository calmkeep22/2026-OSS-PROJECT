package org.ossproject.voice.javasound;

import org.ossproject.voice.AudioCapturePort;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.sound.sampled.Mixer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 마이크에서 한마디를 받아 WAV 로 만든다.
 *
 * <p>16kHz · 16비트 · 모노로 받는다. whisper 가 이 형식으로 학습돼 있어서, 다른
 * 형식으로 넣으면 서버가 다시 변환하거나 정확도가 떨어진다. 더 좋은 품질로 받아도
 * 인식이 나아지지 않는다.
 *
 * <p>말이 끝난 것을 알아채는 판단은 {@link UtteranceDetector} 가 한다. 여기서는
 * 소리를 읽어 크기를 재고 넘겨 줄 뿐이다. 그래야 마이크 없이도 그 판단을 검사할 수
 * 있다.
 */
public final class MicrophoneCapture implements AudioCapturePort {

    /** whisper 가 학습된 형식. 서명 있는 16비트 정수, 리틀엔디언. */
    static final AudioFormat FORMAT = new AudioFormat(16_000f, 16, 1, true, false);

    /** 한 번에 읽을 소리 길이. 짧을수록 말 끝을 빨리 알아채고 CPU 를 조금 더 쓴다. */
    private static final int CHUNK_MILLIS = 50;

    private static final int CHUNK_BYTES =
            (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() * CHUNK_MILLIS / 1000);

    private volatile TargetDataLine line;
    private volatile boolean closed;

    /** 사용자가 고른 장치. 비어 있으면 기본 장치를 쓴다. */
    private volatile String deviceName = "";

    /** 고른 장치가 사라져 기본으로 돌아갔으면 그 사실을 담아 둔다. */
    private volatile String fellBackFrom = "";

    @Override
    public boolean available() {
        return unavailableReason().isEmpty();
    }

    @Override
    public String unavailableReason() {
        if (closed) return "마이크를 이미 닫았습니다.";
        if (!AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT))) {
            return "이 컴퓨터에서 마이크를 찾지 못했습니다. 마이크를 연결하고 다시 시도해주세요.";
        }
        return "";
    }

    @Override
    public List<String> devices() {
        DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);
        List<String> found = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(wanted)) found.add(info.getName());
        }
        return List.copyOf(found);
    }

    @Override
    public void useDevice(String deviceName) {
        this.deviceName = deviceName == null ? "" : deviceName.strip();
        this.fellBackFrom = "";
    }

    @Override
    public String currentDevice() {
        return deviceName;
    }

    /**
     * 고른 장치가 사라져 기본으로 돌아갔다면 그 장치 이름. 아니면 빈 문자열.
     *
     * <p>블루투스 이어폰은 수시로 끊긴다. 조용히 기본으로 바꿔 버리면 사용자는 갑자기
     * 안 되는 이유를 알 수 없다. 화면이 이것을 물어 읽어 준다.
     */
    public String fellBackFrom() {
        return fellBackFrom;
    }

    /**
     * 열어야 할 라인을 고른다.
     *
     * <p>이름을 정해 두지 않았으면 자바의 기본 장치를 쓴다. 다만 그 기본이 늘 맞지는
     * 않는다 — 실측에서 자바 기본은 소리가 하나도 안 들어오는 "주 사운드 캡처 드라이버"
     * 였고, 사용자가 실제로 쓰던 이어폰은 목록의 다른 자리에 있었다. 그래서 고를 수
     * 있게 열어 둔다.
     */
    private TargetDataLine openLine() throws LineUnavailableException {
        DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);
        String wantedName = deviceName;
        if (!wantedName.isEmpty()) {
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                if (!info.getName().equals(wantedName)) continue;
                Mixer mixer = AudioSystem.getMixer(info);
                if (mixer.isLineSupported(wanted)) return (TargetDataLine) mixer.getLine(wanted);
            }
            // 정해 둔 장치가 없어졌다. 기본으로 가되 그 사실을 남긴다.
            fellBackFrom = wantedName;
        }
        return AudioSystem.getTargetDataLine(FORMAT);
    }

    /**
     * 소리 크기를 계속 흘려보낸다.
     *
     * <p>마이크가 잡히는지 사람이 눈과 귀로 확인하는 자리에 쓴다. 판정은 하지 않는다 —
     * 말이 시작됐는지 끝났는지는 {@link UtteranceDetector} 가 볼 일이고, 여기서는 지금
     * 얼마나 들어오는지만 알린다.
     *
     * <p>따로 스레드에서 돈다. 화면 스레드에서 마이크를 읽으면 그동안 화면이 멈춘다.
     * 데몬 스레드라 앱을 내릴 때 이것 때문에 붙들리지 않는다.
     */
    @Override
    public AutoCloseable monitorLevel(java.util.function.DoubleConsumer onLevel) {
        java.util.Objects.requireNonNull(onLevel, "onLevel");
        String reason = unavailableReason();
        if (!reason.isEmpty()) throw new IllegalStateException(reason);

        java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            try (TargetDataLine open = openLine()) {
                open.open(FORMAT, CHUNK_BYTES * 8);
                open.start();
                byte[] chunk = new byte[CHUNK_BYTES];
                while (running.get() && !closed) {
                    int read = open.read(chunk, 0, chunk.length);
                    if (read <= 0) break;
                    onLevel.accept(loudness(chunk, read));
                }
                open.stop();
            } catch (LineUnavailableException | RuntimeException failure) {
                // 확인용 기능이다. 여기서 앱을 세우지 않는다. 값이 안 오면 화면이
                // "소리가 들어오지 않습니다" 로 읽는데, 그것이 사용자가 알아야 할 전부다.
                running.set(false);
            }
        }, "mic-level");
        reader.setDaemon(true);
        reader.start();
        return () -> running.set(false);
    }

    @Override
    public byte[] recordUtterance(Duration limit) {
        String reason = unavailableReason();
        if (!reason.isEmpty()) throw new IllegalStateException(reason);

        long limitMillis = Math.max(CHUNK_MILLIS, limit.toMillis());
        UtteranceDetector detector = new UtteranceDetector(CHUNK_MILLIS);
        ByteArrayOutputStream recorded = new ByteArrayOutputStream();

        try (TargetDataLine open = openLine()) {
            line = open;
            open.open(FORMAT, CHUNK_BYTES * 8);
            open.start();

            byte[] chunk = new byte[CHUNK_BYTES];
            long spent = 0;
            while (!closed && spent < limitMillis) {
                int read = open.read(chunk, 0, chunk.length);
                if (read <= 0) break;
                spent += CHUNK_MILLIS;

                UtteranceDetector.State state = detector.accept(loudness(chunk, read));
                if (detector.recording()) recorded.write(chunk, 0, read);
                if (state == UtteranceDetector.State.DONE) break;
                if (state == UtteranceDetector.State.GAVE_UP) return new byte[0];
            }
            if (detector.timeUp() == UtteranceDetector.State.GAVE_UP) return new byte[0];
        } catch (LineUnavailableException busy) {
            throw new IllegalStateException(
                    "마이크를 다른 프로그램이 쓰고 있습니다. 그 프로그램을 닫고 다시 시도해주세요.", busy);
        } finally {
            line = null;
        }
        return recorded.size() == 0 ? new byte[0] : toWav(recorded.toByteArray());
    }

    /**
     * 소리 한 토막의 크기(RMS). 0.0 부터 1.0.
     *
     * <p>평균이 아니라 제곱평균을 쓴다. 소리는 0 을 중심으로 위아래로 흔들리므로
     * 그냥 평균 내면 큰 소리도 0 에 가까워진다.
     */
    static double loudness(byte[] chunk, int length) {
        long squares = 0;
        int samples = length / 2;
        if (samples == 0) return 0.0;
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (short) ((chunk[index + 1] << 8) | (chunk[index] & 0xff));
            squares += (long) sample * sample;
        }
        return Math.sqrt((double) squares / samples) / Short.MAX_VALUE;
    }

    static byte[] toWav(byte[] pcm) {
        ByteArrayOutputStream wav = new ByteArrayOutputStream(pcm.length + 64);
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), FORMAT, pcm.length / FORMAT.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, wav);
        } catch (IOException impossible) {
            throw new IllegalStateException("메모리에 WAV 를 쓰지 못했습니다.", impossible);
        }
        return wav.toByteArray();
    }

    /**
     * 녹음을 멈춘다.
     *
     * <p>{@link #recordUtterance} 는 다른 스레드에서 돌고 있다. 사용자가 화면을 닫거나
     * 취소했을 때 말이 끝나기를 기다리게 두면 앱이 안 꺼진다.
     */
    @Override
    public void close() {
        closed = true;
        TargetDataLine running = line;
        if (running != null) running.stop();
    }
}
