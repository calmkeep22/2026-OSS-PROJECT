package org.ossproject.ai;

import java.math.BigDecimal;

/**
 * 차트가 닮은 종목.
 *
 * <p>과거 모양이 비슷하다는 뜻이다. 앞으로 같이 움직인다는 뜻이 아니다. 이 구분을
 * 흐리면 사용자가 닮은 종목을 대체재로 오해한다.
 */
public record SimilarStock(String symbol, String name, BigDecimal similarityPercent) {

    public SimilarStock {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        name = name == null || name.isBlank() ? symbol : name;
        similarityPercent = similarityPercent == null ? BigDecimal.ZERO : similarityPercent;
    }

    public String describe() {
        return name + " 유사도 " + similarityPercent.stripTrailingZeros().toPlainString() + "퍼센트";
    }
}
