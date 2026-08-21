package org.ossproject.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 이상 움직임과 위험도.
 *
 * <p>평소와 얼마나 다른지를 본다. 이상 여부만 알리고 사고팔라고 하지 않는다.
 *
 * @param grade      이례적 정도. 서비스가 준 등급 그대로
 * @param direction  상승인지 하락인지
 * @param riskGrade  이 종목 자체의 위험도. 비교군 대비 백분위로 매긴 값이다
 * @param riskAdvice 위험도에 딸린 조언. 종목 추천이 아니라 다루는 방법에 대한 것이다
 */
public record AnomalySignal(
        boolean unusual,
        String grade,
        String direction,
        LocalDate observedOn,
        BigDecimal changePercent,
        String riskGrade,
        String riskAdvice
) {
    public AnomalySignal {
        grade = grade == null ? "" : grade;
        direction = direction == null ? "" : direction;
        riskGrade = riskGrade == null ? "" : riskGrade;
        riskAdvice = riskAdvice == null ? "" : riskAdvice;
    }

    public Optional<String> riskAdviceIfPresent() {
        return riskAdvice.isBlank() ? Optional.empty() : Optional.of(riskAdvice);
    }
}
