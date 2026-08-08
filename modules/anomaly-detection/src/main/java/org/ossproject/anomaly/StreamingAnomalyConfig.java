package org.ossproject.anomaly;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 실시간 이상 탐지 설정.
 *
 * @param window                 이상 여부를 판단하는 관측 구간
 * @param baselineWindow         거래량 기준선을 계산하는 더 긴 구간
 * @param priceThresholdPercent  구간 내 가격 변동률 임계값(%)
 * @param volumeThresholdRatio   기준선 대비 거래량 배수 임계값
 * @param cooldown               같은 종목·같은 유형의 알림을 다시 내보내지 않는 시간
 * @param minimumSamples         판단에 필요한 최소 시세 개수
 */
public record StreamingAnomalyConfig(
        Duration window,
        Duration baselineWindow,
        BigDecimal priceThresholdPercent,
        BigDecimal volumeThresholdRatio,
        Duration cooldown,
        int minimumSamples
) {
    public StreamingAnomalyConfig {
        requirePositive(window, "관측 구간");
        requirePositive(baselineWindow, "기준선 구간");
        if (baselineWindow.compareTo(window) <= 0) {
            throw new IllegalArgumentException("기준선 구간은 관측 구간보다 길어야 합니다.");
        }
        if (priceThresholdPercent == null || priceThresholdPercent.signum() <= 0) {
            throw new IllegalArgumentException("가격 임계값은 0보다 커야 합니다.");
        }
        if (volumeThresholdRatio == null || volumeThresholdRatio.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("거래량 배수 임계값은 1보다 커야 합니다.");
        }
        if (cooldown == null || cooldown.isNegative()) {
            throw new IllegalArgumentException("알림 억제 시간은 0 이상이어야 합니다.");
        }
        if (minimumSamples < 2) {
            throw new IllegalArgumentException("최소 시세 개수는 2 이상이어야 합니다.");
        }
    }

    /**
     * 기본값. 1분 구간에서 2% 변동, 10분 기준선 대비 거래량 3배, 같은 알림은 3분간 억제.
     *
     * <p>억제 시간을 넉넉히 잡은 이유는, 알림이 음성으로 나가기 때문이다. 화면이라면
     * 여러 개가 쌓여도 눈으로 훑고 넘길 수 있지만, 음성은 하나씩 순서대로 읽히므로
     * 알림이 몰리면 사용자가 아무것도 못 하게 된다.
     */
    public static StreamingAnomalyConfig defaults() {
        return new StreamingAnomalyConfig(
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                new BigDecimal("2.0"),
                new BigDecimal("3.0"),
                Duration.ofMinutes(3),
                3);
    }

    private static void requirePositive(Duration duration, String label) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(label + "은(는) 0보다 커야 합니다.");
        }
    }
}
