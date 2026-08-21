package org.ossproject.ai.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ossproject.ai.SimilarStock;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서비스는 유사도를 0~1 비율로 보낸다. 그대로 퍼센트라고 부르면 87퍼센트가 0.87퍼센트로
 * 읽혀, 닮은 종목을 전혀 닮지 않은 것으로 전하게 된다.
 */
class SimilarityScaleTest {

    @Test
    @DisplayName("유사도는 퍼센트로 읽힌다")
    void similarityReadsAsPercent() {
        SimilarStock stock = new SimilarStock("000660", "ISC", new BigDecimal("87"));

        assertTrue(stock.describe().contains("87퍼센트"), stock.describe());
    }
}
