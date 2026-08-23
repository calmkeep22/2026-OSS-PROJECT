import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * 마이크로 소리가 들어오는지 눈으로 보는 도구.
 *
 * <pre>
 *   java tools\MicMeter.java            기본 장치
 *   java tools\MicMeter.java 2          목록의 2번 장치
 * </pre>
 *
 * <p>빌드를 거치지 않는다. Gradle 로 돌리면 출력이 모여 있다가 한꺼번에 나와서,
 * 말하라는 안내를 제때 볼 수 없다. 그래서 검사가 아니라 따로 실행하는 프로그램으로
 * 두었다. 막대가 실시간으로 움직이는 것을 보면서 말하면 된다.
 */
public final class MicMeter {

    /** 앱이 쓰는 것과 같은 형식. 여기서 잡히면 앱에서도 잡힌다. */
    private static final AudioFormat FORMAT = new AudioFormat(16_000f, 16, 1, true, false);

    /** 앱이 "말이다" 라고 판단하는 최소 크기. 막대가 여기를 넘어야 한다. */
    private static final double THRESHOLD = 0.012;

    private static final int SECONDS = 15;

    public static void main(String[] args) throws Exception {
        DataLine.Info wanted = new DataLine.Info(TargetDataLine.class, FORMAT);

        Mixer.Info[] all = AudioSystem.getMixerInfo();
        java.util.List<Mixer.Info> usable = new java.util.ArrayList<>();
        for (Mixer.Info info : all) {
            if (AudioSystem.getMixer(info).isLineSupported(wanted)) usable.add(info);
        }

        System.out.println();
        System.out.println("입력 장치:");
        for (int index = 0; index < usable.size(); index++) {
            System.out.println("  " + (index + 1) + ") " + usable.get(index).getName());
        }
        if (usable.isEmpty()) {
            System.out.println("  없습니다. 이 형식을 받는 입력 장치가 하나도 없습니다.");
            return;
        }

        int pick = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        TargetDataLine line = pick > 0 && pick <= usable.size()
                ? (TargetDataLine) AudioSystem.getMixer(usable.get(pick - 1)).getLine(wanted)
                : AudioSystem.getTargetDataLine(FORMAT);
        System.out.println();
        System.out.println("고른 장치: " + (pick > 0 ? usable.get(pick - 1).getName() : "기본 장치"));
        System.out.println("문턱값 " + THRESHOLD + " 을 넘으면 [넘음] 이 뜹니다.");
        System.out.println();
        System.out.println(">>> 지금부터 " + SECONDS + "초. 마이크에 대고 말해 보세요. <<<");
        System.out.println();

        int chunkBytes = (int) (FORMAT.getSampleRate() * FORMAT.getFrameSize() / 10);  // 100ms
        byte[] buffer = new byte[chunkBytes];
        double loudest = 0.0;
        int over = 0;

        line.open(FORMAT, chunkBytes * 8);
        line.start();
        for (int tick = 0; tick < SECONDS * 10; tick++) {
            int read = line.read(buffer, 0, buffer.length);
            if (read <= 0) break;
            double level = loudness(buffer, read);
            loudest = Math.max(loudest, level);
            if (level >= THRESHOLD) over++;
            if (tick % 2 == 0) System.out.println(bar(level));
        }
        line.stop();
        line.close();

        System.out.println();
        System.out.println("=================================");
        System.out.printf("  가장 큰 소리   %.4f%n", loudest);
        System.out.printf("  문턱값         %.4f%n", THRESHOLD);
        System.out.printf("  문턱 넘은 구간 %d 번%n", over);
        System.out.println(loudest >= THRESHOLD
                ? "  판정: 마이크가 잡힙니다. 앱에서도 됩니다."
                : "  판정: 아직 안 잡힙니다. 다른 번호의 장치로 다시 해보세요.");
        System.out.println("=================================");
    }

    private static String bar(double level) {
        int filled = (int) Math.min(40, Math.round(level * 400));
        StringBuilder drawn = new StringBuilder("  |");
        for (int at = 0; at < 40; at++) drawn.append(at < filled ? '#' : ' ');
        drawn.append(String.format("| %.4f", level));
        if (level >= THRESHOLD) drawn.append("  [넘음]");
        return drawn.toString();
    }

    /** 제곱평균. 소리는 0을 중심으로 흔들리므로 그냥 평균 내면 큰 소리도 0에 가깝다. */
    private static double loudness(byte[] chunk, int length) {
        long squares = 0;
        int samples = length / 2;
        if (samples == 0) return 0.0;
        for (int index = 0; index + 1 < length; index += 2) {
            int sample = (short) ((chunk[index + 1] << 8) | (chunk[index] & 0xff));
            squares += (long) sample * sample;
        }
        return Math.sqrt((double) squares / samples) / Short.MAX_VALUE;
    }
}
