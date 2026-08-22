package org.ossproject.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 다음 거래일 예측.
 *
 * <p>기본은 변동성이다. 크게 움직일지 잔잔할지를 본다. 방향(오를지 내릴지)도 받을 수
 * 있지만 검증에서 우연과 구별되지 않았다. 차익거래로 지워지기 때문이다. 누가 내일 오를
 * 것을 알면 오늘 산다.
 *
 * @param target        무엇을 예측했는지. 변동성 또는 방향
 * @param verdict       판정. "크게움직임" 또는 "상승" 처럼 서비스가 준 말 그대로
 * @param probability   판정 쪽 확률(%). 서비스가 임계값을 50 으로 옮긴 뒤 계산한 값이라
 *                      판정과 항상 같은 쪽을 가리킨다
 * @param meaningful    검증에서 우연과 구별되었는지. 거짓이면 그 사실을 함께 보여야 한다
 * @param todaysSession 대상일이 오늘인지. 장중이면 오늘, 마감 뒤면 다음 거래일이다.
 *                      매매 시점이라 모호하면 안 된다
 */
public record Forecast(
        String target,
        String verdict,
        BigDecimal probability,
        LocalDate targetDate,
        boolean todaysSession,
        boolean meaningful
) {
    public Forecast {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("예측 대상은 필수입니다.");
        }
        if (verdict == null || verdict.isBlank()) {
            throw new IllegalArgumentException("판정은 필수입니다.");
        }
        if (probability == null || probability.signum() < 0) {
            throw new IllegalArgumentException("확률은 0 이상이어야 합니다.");
        }
    }

    /**
     * 읽어 줄 한 문장.
     *
     * <p>확률을 앞에 두지 않는다. 숫자가 먼저 나오면 스크린리더 사용자는 그 숫자가 무엇에
     * 대한 것인지 모른 채 듣게 된다.
     */
    public String narration() {
        return sessionText() + " " + target + " 예측은 " + verdict + "입니다. 확률 "
                + probability.setScale(1, RoundingMode.HALF_UP).toPlainString() + "퍼센트.";
    }

    /** 언제를 가리키는 예측인지. 날짜보다 이 구분을 믿는 편이 안전하다. */
    public String sessionText() {
        return todaysSession ? "오늘" : "다음 거래일";
    }
}
