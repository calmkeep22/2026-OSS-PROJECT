package org.ossproject.ai;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 차트가 닮은 종목.
 *
 * <p>과거 모양이 비슷하다는 뜻이다. 앞으로 같이 움직인다는 뜻이 아니다. 이 구분을
 * 흐리면 사용자가 닮은 종목을 대체재로 오해한다.
 *
 * @param similarityPercent 모양이 얼마나 닮았나. 형태 0.7 · 진폭 0.2 · 수익률 0.1
 * @param comovementPercent 봉마다 실제로 함께 움직였나. 유사도와 다른 질문이다
 */
public record SimilarStock(String symbol, String name, BigDecimal similarityPercent,
                           Optional<BigDecimal> comovementPercent) {

    public SimilarStock {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        name = name == null || name.isBlank() ? symbol : name;
        similarityPercent = similarityPercent == null ? BigDecimal.ZERO : similarityPercent;
        comovementPercent = comovementPercent == null ? Optional.empty() : comovementPercent;
    }

    public SimilarStock(String symbol, String name, BigDecimal similarityPercent) {
        this(symbol, name, similarityPercent, Optional.empty());
    }

    /**
     * 읽어 줄 한 마디.
     *
     * <p>모양이 0.98 로 닮았는데 함께 움직인 정도는 0.21 인 짝이 흔하다. 다른 시기의 다른
     * 종목이 우연히 같은 곡선을 그린 경우다. 유사도만 말하면 그 둘이 구별되지 않는다.
     */
    public String describe() {
        String head = name + " 유사도 " + percent(similarityPercent) + "퍼센트";
        return comovementPercent
                .map(value -> head + ", 함께 움직인 정도 " + percent(value) + "퍼센트")
                .orElse(head);
    }

    private static String percent(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
