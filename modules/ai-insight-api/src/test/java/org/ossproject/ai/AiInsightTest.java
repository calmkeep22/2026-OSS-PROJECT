package org.ossproject.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 분석 결과에는 반드시 함께 전해야 하는 단서가 있다. 화면이 고를 수 있는 목록이 아니다.
 */
class AiInsightTest {

    private static AiInsight insight(Confidence confidence, boolean meaningful) {
        return AiInsight.of("005930", "삼성전자", "삼성전자가 오늘 크게 움직일 확률 52퍼센트입니다.",
                confidence, meaningful);
    }

    /** 투자 권유가 아니라는 말은 어떤 경우에도 빠지지 않는다. */
    @Test
    @DisplayName("언제나 투자 권유가 아님을 함께 전한다")
    void alwaysSaysItIsNotAdvice() {
        List<String> caveats = insight(Confidence.HIGH, true).requiredCaveats();

        assertFalse(caveats.isEmpty());
        assertTrue(caveats.stream().anyMatch(c -> c.contains("투자 권유가 아닙니다")), caveats.toString());
    }

    /** 신뢰도가 낮은데 숫자만 전하면 근거가 얇은 값을 확실한 것으로 읽는다. */
    @Test
    @DisplayName("신뢰도가 낮으면 그 사실을 함께 전한다")
    void warnsWhenConfidenceIsLow() {
        assertTrue(insight(Confidence.LOW, true).requiredCaveats().stream()
                .anyMatch(c -> c.contains("신뢰도")));
        assertTrue(insight(Confidence.VERY_LOW, true).requiredCaveats().stream()
                .anyMatch(c -> c.contains("신뢰도")));
        assertFalse(insight(Confidence.HIGH, true).requiredCaveats().stream()
                .anyMatch(c -> c.contains("신뢰도")));
    }

    /**
     * 방향 예측은 검증에서 우연과 구별되지 않았다. 이 사실을 숨기면 사용자가 동전 던지기를
     * 신호로 읽는다.
     */
    @Test
    @DisplayName("유의미하지 않은 예측은 그 사실을 함께 전한다")
    void warnsWhenTheForecastIsNotMeaningful() {
        assertTrue(insight(Confidence.HIGH, false).requiredCaveats().stream()
                .anyMatch(c -> c.contains("우연과 구별되지 않았습니다")));
    }

    /** 닮은 차트를 대체재로 오해하면 안 된다. */
    @Test
    @DisplayName("닮은 종목이 있으면 예측이 아니라는 사실을 함께 전한다")
    void warnsThatSimilarityIsNotAForecast() {
        AiInsight withSimilar = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("77"))), Map.of());

        assertTrue(withSimilar.requiredCaveats().stream()
                .anyMatch(c -> c.contains("같이 움직인다는 뜻이 아닙니다")));
    }

    /** 읽어 주는 문장은 문안과 단서를 합친 것이다. 화면이 따로 조립하지 않는다. */
    @Test
    @DisplayName("전체 문장은 문안 뒤에 단서를 잇는다")
    void fullNarrationAppendsCaveats() {
        String full = insight(Confidence.LOW, false).fullNarration();

        assertTrue(full.startsWith("삼성전자가"), full);
        assertTrue(full.contains("신뢰도"), full);
        assertTrue(full.contains("우연과"), full);
    }

    /** 일부가 실패한 것을 조용히 빼면 사용자는 그 항목이 정상이라고 오해한다. */
    @Test
    @DisplayName("일부 실패를 감추지 않는다")
    void reportsPartialFailures() {
        AiInsight partial = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), List.of(),
                Map.of("유사도", "ReferenceMissing"));

        assertTrue(partial.hasPartialFailure());
        assertTrue(partial.partialFailureText().orElseThrow().contains("유사도"));
    }

    @Test
    @DisplayName("읽어 줄 문장이 없으면 만들지 않는다")
    void rejectsAnEmptyNarration() {
        assertThrows(IllegalArgumentException.class,
                () -> AiInsight.of("005930", "삼성전자", "  ", Confidence.HIGH, true));
    }

    @Test
    @DisplayName("모르는 신뢰도 표기는 알 수 없음으로 두고 경고한다")
    void treatsUnknownConfidenceAsWorthWarning() {
        assertEquals(Confidence.UNKNOWN, Confidence.from("이상한값"));
        assertTrue(Confidence.UNKNOWN.needsWarning());
        assertTrue(Confidence.from("높음").needsWarning() == false);
    }
}
