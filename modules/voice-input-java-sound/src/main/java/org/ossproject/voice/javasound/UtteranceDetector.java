package org.ossproject.voice.javasound;

/**
 * 말이 시작되고 끝난 것을 소리 크기만 보고 판단한다.
 *
 * <p>마이크 없이 검사할 수 있도록 소리 장치를 모르게 두었다. 크기 값을 순서대로
 * 넣어 주면 상태가 바뀐다. 이 판단이 틀리면 말을 하는 중에 끊기거나 조용해진 뒤에도
 * 계속 녹음하는데, 둘 다 마이크를 붙여야만 드러나는 종류의 고장이다.
 *
 * <p>문턱값을 고정해 두지 않는다. 조용한 방과 카페의 배경 소음이 열 배 넘게 차이
 * 난다. 고정값을 쓰면 한쪽에서는 말을 못 알아채고 다른 쪽에서는 영영 안 끊긴다.
 * 그래서 처음 얼마간을 배경 소음으로 재고 그보다 훨씬 큰 소리만 말로 친다.
 */
public final class UtteranceDetector {

    /** 배경 소음을 재는 동안. 이 시간에 말을 시작하면 그 말이 소음으로 잡힌다. */
    private static final int CALIBRATION_MILLIS = 300;

    /** 배경 소음의 몇 배부터 말로 칠지. */
    private static final double SPEECH_OVER_NOISE = 3.0;

    /**
     * 배경 소음으로 인정할 최대 크기.
     *
     * <p>사람은 단추를 누르자마자 말한다. 그러면 배경을 재는 300밀리초에 말소리가
     * 섞여 들어가 배경이 실제보다 훨씬 크게 잡히고, 문턱이 따라 올라가 진짜 말이
     * 조용한 것으로 보인다. 그 상태로 700밀리초가 지나면 말하는 도중에 끊긴다.
     *
     * <p>실제로 그렇게 잘렸다. 2.6초짜리 말이 1.6초만 녹음됐고, 인식기는 그 토막에
     * 대고 엉뚱한 말을 지어냈다. 방 소음이 이보다 클 수는 없으므로, 이보다 크게
     * 재졌다면 말소리가 섞인 것으로 보고 여기서 자른다.
     */
    private static final double NOISE_CEILING = 0.03;

    /**
     * 이만큼은 말해야 한마디로 친다.
     *
     * <p>문턱을 잘못 잡아도 곧바로 끊기지 않게 하는 안전장치다. 짧게 끊긴 소리는
     * 인식기가 지어내기 좋은 먹이가 된다.
     */
    private static final int MIN_SPEECH_MILLIS = 400;

    /** 아주 조용한 방에서 배경이 0에 가까울 때 쓰는 바닥값. */
    /** 마이크 확인 화면과 같은 값을 쓴다. 갈라지면 막대는 넘는데 인식은 안 된다. */
    private static final double FLOOR = org.ossproject.voice.AudioCapturePort.SPEECH_FLOOR;

    /**
     * 말이 그친 뒤 이만큼 조용하면 끝난 것으로 본다.
     *
     * <p>한국어는 어절 사이가 길다. 짧게 잡으면 "이상감지 … 페이지로 이동해줘" 의
     * 가운데서 끊긴다.
     */
    private static final int TRAILING_SILENCE_MILLIS = 900;

    /** 이 시간 안에 말이 시작되지 않으면 포기한다. */
    private static final int LEAD_IN_MILLIS = 4000;

    public enum State {
        /** 배경 소음을 재는 중. */
        CALIBRATING,
        /** 말이 시작되기를 기다리는 중. */
        WAITING,
        /** 말하는 중. */
        SPEAKING,
        /** 말이 끝났다. 녹음한 것을 보낸다. */
        DONE,
        /** 아무 말도 하지 않았다. 보낼 것이 없다. */
        GAVE_UP
    }

    private final int chunkMillis;
    private State state = State.CALIBRATING;
    private double noise;
    private int noiseSamples;
    private int elapsed;
    private int silence;
    private int spoken;

    /** @param chunkMillis {@link #accept} 한 번이 소리 몇 밀리초에 해당하는지 */
    public UtteranceDetector(int chunkMillis) {
        if (chunkMillis <= 0) throw new IllegalArgumentException("chunkMillis: " + chunkMillis);
        this.chunkMillis = chunkMillis;
    }

    /**
     * 소리 한 토막의 크기를 넣는다.
     *
     * @param level 0.0 부터 1.0 사이의 크기(RMS)
     * @return 넣고 난 뒤의 상태
     */
    public State accept(double level) {
        if (state == State.DONE || state == State.GAVE_UP) return state;
        elapsed += chunkMillis;

        if (state == State.CALIBRATING) {
            noise += level;
            noiseSamples++;
            if (elapsed >= CALIBRATION_MILLIS) {
                double measured = noiseSamples == 0 ? 0.0 : noise / noiseSamples;
                // 말소리가 섞여 들어왔으면 배경으로 인정하지 않는다.
                noise = Math.min(measured, NOISE_CEILING);
                state = State.WAITING;
            }
            return state;
        }

        boolean loud = level >= threshold();
        if (state == State.WAITING) {
            if (loud) {
                state = State.SPEAKING;
                silence = 0;
                // 말이 시작된 이 토막도 말한 시간에 넣는다. 빼먹으면 짧은 한마디가
                // 최소 길이에 못 미쳐 영영 끝나지 않는다.
                spoken += chunkMillis;
            } else if (elapsed >= LEAD_IN_MILLIS) {
                state = State.GAVE_UP;
            }
            return state;
        }

        // SPEAKING
        if (loud) {
            silence = 0;
            spoken += chunkMillis;
        } else {
            silence += chunkMillis;
            // 아직 말한 양이 적으면 기다린다. 문턱을 잘못 잡았을 때 첫 음절만 담고
            // 끊어 버리는 것을 막는다.
            if (silence >= TRAILING_SILENCE_MILLIS && spoken >= MIN_SPEECH_MILLIS) {
                state = State.DONE;
            }
        }
        return state;
    }

    /** 시간이 다 됐다. 말하던 중이었으면 거기까지를 한마디로 친다. */
    public State timeUp() {
        if (state == State.DONE || state == State.GAVE_UP) return state;
        state = state == State.SPEAKING ? State.DONE : State.GAVE_UP;
        return state;
    }

    public State state() {
        return state;
    }

    /**
     * 지금 들어오는 소리를 녹음에 담아야 하는가.
     *
     * <p>말이 시작되기 전 것도 담는다. 사람은 단추를 누르자마자 말을 시작하고,
     * 크기로 판단하면 첫 음절이 이미 지나간 뒤에야 "말이다" 를 알게 된다. 앞을
     * 버리면 "삼성전자" 가 "성전자" 로 들어간다.
     */
    public boolean recording() {
        return state == State.WAITING || state == State.SPEAKING;
    }

    double threshold() {
        return Math.max(FLOOR, noise * SPEECH_OVER_NOISE);
    }
}
