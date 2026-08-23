package org.ossproject.voice.javasound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.ossproject.voice.javasound.UtteranceDetector.State.*;

/**
 * 말이 시작되고 끝난 것을 알아채는지.
 *
 * <p>마이크를 붙이지 않고 검사한다. 소리 크기만 순서대로 넣으면 되므로 CI 에서도
 * 돈다. 이 판단이 틀리면 말하는 중에 잘리거나 조용해진 뒤에도 안 끊기는데, 둘 다
 * 오류를 내지 않는다 — 사람이 마이크에 대고 말해 봐야만 드러난다.
 */
class UtteranceDetectorTest {

    private static final int CHUNK = 50;

    /** 조용한 방. */
    private static final double QUIET = 0.002;
    /** 말소리. */
    private static final double SPEECH = 0.15;

    private final UtteranceDetector detector = new UtteranceDetector(CHUNK);

    private void feed(double level, int millis) {
        for (int spent = 0; spent < millis; spent += CHUNK) detector.accept(level);
    }

    @Nested
    @DisplayName("보통 한마디")
    class NormalUtterance {

        @Test void 말이_시작되면_알아챈다() {
            feed(QUIET, 300);
            detector.accept(SPEECH);

            assertEquals(SPEAKING, detector.state());
        }

        @Test void 말이_그치고_잠시_뒤에_끝난다() {
            feed(QUIET, 300);
            feed(SPEECH, 800);
            assertEquals(SPEAKING, detector.state(), "아직 말하는 중이어야 한다");

            feed(QUIET, 950);

            assertEquals(DONE, detector.state());
        }

        /**
         * 말하다 숨 쉬는 것으로 끊으면 안 된다. "삼성전자 (숨) 매수 열 주" 가
         * "삼성전자" 에서 잘리면 수량 없는 주문이 되어 되묻게 된다.
         */
        @Test void 말_중간의_짧은_쉼으로는_끊지_않는다() {
            feed(QUIET, 300);
            feed(SPEECH, 400);
            feed(QUIET, 300);
            feed(SPEECH, 400);

            assertEquals(SPEAKING, detector.state());
        }
    }

    @Nested
    @DisplayName("아무 말도 안 했을 때")
    class NoSpeech {

        @Test void 한참_조용하면_포기한다() {
            feed(QUIET, 5000);

            assertEquals(GAVE_UP, detector.state());
        }

        @Test void 포기했으면_보낼_것이_없다() {
            feed(QUIET, 5000);

            assertFalse(detector.recording(), "빈 소리를 서버로 보내면 안 된다");
        }

        @Test void 시간이_다_됐는데_말을_안_했으면_포기다() {
            feed(QUIET, 300);

            assertEquals(GAVE_UP, detector.timeUp());
        }
    }

    @Nested
    @DisplayName("단추를 누르자마자 말을 시작해도 잘리지 않는다")
    class SpeaksImmediately {

        /**
         * 실제로 났던 고장이다.
         *
         * <p>사람은 단추를 누르자마자 말한다. 그러면 배경을 재는 300밀리초에 말소리가
         * 섞여 배경이 크게 잡히고, 문턱이 따라 올라가 진짜 말이 조용한 것으로 보인다.
         * 2.6초짜리 말이 1.6초만 녹음됐고, 인식기는 그 토막에 대고 어휘 목록을 그대로
         * 뱉었다. 사용자는 "이상감지" 라고 말했는데 "삼성전자" 로 들렸다.
         */
        @Test void 배경을_재는_동안_말해도_문턱이_치솟지_않는다() {
            feed(SPEECH, 300);   // 배경을 재는 내내 말하는 중

            assertTrue(detector.threshold() < SPEECH,
                    "문턱이 말소리보다 높으면 그 뒤로 아무것도 말로 안 잡힌다: "
                            + detector.threshold());
        }

        @Test void 처음부터_말해도_한마디를_끝까지_담는다() {
            feed(SPEECH, 300);   // 배경 재는 구간
            feed(SPEECH, 1300);  // 이어지는 말
            feed(QUIET, 400);    // 어절 사이 쉼

            assertEquals(SPEAKING, detector.state(), "말하는 중에 끊겼다");

            feed(SPEECH, 800);
            feed(QUIET, 950);

            assertEquals(DONE, detector.state());
        }

        /** 첫 음절만 담고 끊으면 인식기가 지어낸다. 최소한은 말해야 한마디로 친다. */
        @Test void 말한_양이_적으면_아직_끝내지_않는다() {
            feed(QUIET, 300);
            feed(SPEECH, 150);   // 아주 짧은 한 음절
            feed(QUIET, 950);

            assertNotEquals(DONE, detector.state(),
                    "첫 음절만 담고 끊으면 인식기가 그 토막에 대고 지어낸다");
        }
    }

    @Nested
    @DisplayName("시끄러운 곳")
    class NoisyRoom {

        /**
         * 카페의 배경 소음은 조용한 방의 열 배가 넘는다. 문턱값을 고정해 두면
         * 시끄러운 곳에서 배경 소음이 말로 잡혀 영영 안 끊긴다.
         */
        @Test void 배경이_시끄러우면_문턱도_올라간다() {
            UtteranceDetector quiet = new UtteranceDetector(CHUNK);
            for (int spent = 0; spent < 300; spent += CHUNK) quiet.accept(0.002);

            UtteranceDetector noisy = new UtteranceDetector(CHUNK);
            for (int spent = 0; spent < 300; spent += CHUNK) noisy.accept(0.04);

            assertTrue(noisy.threshold() > quiet.threshold(),
                    "시끄러운 곳에서 문턱이 그대로면 배경 소음이 말로 잡힌다");
        }

        @Test void 배경_소음만으로는_말이_시작되지_않는다() {
            double noise = 0.04;
            feed(noise, 300);
            feed(noise, 1000);

            assertNotEquals(SPEAKING, detector.state());
        }

        /**
         * 아주 조용한 방에서 배경이 0 에 가까우면, 배경의 세 배도 0 에 가깝다.
         * 바닥값이 없으면 마이크 잡음 한 톨에 말이 시작된 것으로 친다.
         */
        @Test void 완전히_조용해도_바닥값_아래는_말이_아니다() {
            feed(0.0, 300);

            detector.accept(0.005);

            assertNotEquals(SPEAKING, detector.state());
        }
    }

    @Nested
    @DisplayName("녹음에 담는 범위")
    class WhatGetsRecorded {

        /**
         * 말이 "시작됐다" 고 판단하기 전 것도 담아야 한다. 사람은 단추를 누르자마자
         * 말하고, 크기로 판단하면 첫 음절이 지나간 뒤에야 알게 된다. 앞을 버리면
         * "삼성전자" 가 "성전자" 로 들어가고, 그러면 종목을 못 찾아 되묻게 된다.
         */
        @Test void 말이_시작되기_전부터_담는다() {
            feed(QUIET, 300);

            assertTrue(detector.recording(), "판단 전 소리를 버리면 첫 음절이 잘린다");
        }

        @Test void 끝난_뒤에는_담지_않는다() {
            feed(QUIET, 300);
            feed(SPEECH, 400);
            feed(QUIET, 950);

            assertEquals(DONE, detector.state());
            assertFalse(detector.recording());
        }

        @Test void 끝난_뒤에_더_넣어도_상태가_바뀌지_않는다() {
            feed(QUIET, 300);
            feed(SPEECH, 400);
            feed(QUIET, 950);

            feed(SPEECH, 500);

            assertEquals(DONE, detector.state(), "이미 보낸 한마디에 뒷말이 붙으면 안 된다");
        }
    }

    @Nested
    @DisplayName("시간이 다 됐을 때")
    class TimeUp {

        @Test void 말하던_중이었으면_거기까지를_한마디로_친다() {
            feed(QUIET, 300);
            feed(SPEECH, 2000);

            assertEquals(DONE, detector.timeUp());
        }

        @Test void 이미_끝났으면_그대로_둔다() {
            feed(QUIET, 300);
            feed(SPEECH, 400);
            feed(QUIET, 950);

            assertEquals(DONE, detector.timeUp());
        }
    }
}
