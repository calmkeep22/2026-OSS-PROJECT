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
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("77"))), Optional.empty(), Map.of());

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
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                Optional.empty(), Map.of("유사도", "ReferenceMissing"));

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

    /**
     * 방향 예측은 검증에서 우연과 구별되지 않았다. 차익거래로 지워지기 때문이다. 누가
     * 내일 오를 것을 알면 오늘 산다. 이 사실을 빼면 동전 던지기를 신호로 읽는다.
     */
    @Test
    @DisplayName("방향 예측을 보여 줄 때는 우연과 구별되지 않았다는 사실을 함께 전한다")
    void warnsAboutTheDirectionForecast() {
        Forecast direction = new Forecast("방향", "상승", new BigDecimal("52.9"),
                java.time.LocalDate.of(2026, 8, 24), false, false);
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.of(direction), Optional.empty(), List.of(), Optional.empty(), Map.of());

        assertTrue(insight.requiredCaveats().stream()
                .anyMatch(c -> c.contains("오를지 내릴지")), insight.requiredCaveats().toString());
    }

    /** 검증을 통과한 예측까지 싸잡아 경고하면 경고가 무뎌진다. */
    @Test
    @DisplayName("검증을 통과한 방향 예측에는 경고를 붙이지 않는다")
    void staysQuietWhenTheDirectionForecastIsMeaningful() {
        Forecast direction = new Forecast("방향", "상승", new BigDecimal("52.9"),
                java.time.LocalDate.of(2026, 8, 24), false, true);
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.of(direction), Optional.empty(), List.of(), Optional.empty(), Map.of());

        assertFalse(insight.requiredCaveats().stream()
                .anyMatch(c -> c.contains("오를지 내릴지")));
    }

    /**
     * 위험도는 문안에 없다. 같은 이상 신호라도 평소 크게 흔들리는 종목과 잔잔한 종목은
     * 뜻이 다르다. 빠지면 사용자는 그 차이를 알 방법이 없다.
     */
    @Test
    @DisplayName("위험도와 조언을 읽어 줄 문장으로 만든다")
    void readsOutTheRiskGrade() {
        AnomalySignal signal = new AnomalySignal(true, "강함", "하락",
                java.time.LocalDate.of(2026, 8, 21), new BigDecimal("-4.2"),
                "상위 12퍼센트", "한 번에 사지 말고 나누어 담으세요.");
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.of(signal), List.of(), Optional.empty(), Map.of());

        assertEquals("이 종목의 위험도는 상위 12퍼센트입니다. 한 번에 사지 말고 나누어 담으세요.",
                insight.riskText().orElseThrow());
        assertTrue(insight.fullNarration().contains("상위 12퍼센트"));
    }

    /** 위험도를 받지 못했으면 지어내지 않는다. 지어낸 문장은 실제와 구별되지 않는다. */
    @Test
    @DisplayName("위험도가 없으면 문장을 만들지 않는다")
    void staysSilentWithoutARiskGrade() {
        AnomalySignal signal = new AnomalySignal(false, "약함", "상승",
                java.time.LocalDate.of(2026, 8, 21), new BigDecimal("0.3"), "", "");
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.of(signal), List.of(), Optional.empty(), Map.of());

        assertTrue(insight.riskText().isEmpty());
    }

    /**
     * 문안은 가장 닮은 하나만 말한다. 하나만 들으면 그 종목이 특별해 보이는데 실제로는
     * 비슷한 후보가 여럿이고 그중 첫째일 뿐이다.
     */
    @Test
    @DisplayName("닮은 종목을 받은 만큼 모두 읽어 준다")
    void readsOutEverySimilarStock() {
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("88")),
                        new SimilarStock("035420", "NAVER", new BigDecimal("85"))),
                Optional.empty(), Map.of());

        String text = insight.similarText().orElseThrow();
        assertTrue(text.contains("SK하이닉스"), text);
        assertTrue(text.contains("NAVER"), text);
    }

    /** 화면 글자와 음성이 다르면 스크린리더 사용자만 다른 내용을 듣는다. */
    @Test
    @DisplayName("읽어 주는 문장에 방향·위험도·닮은 차트가 모두 들어간다")
    void spokenTextCarriesEveryPart() {
        Forecast direction = new Forecast("방향", "상승", new BigDecimal("52.9"),
                java.time.LocalDate.of(2026, 8, 24), false, false);
        AnomalySignal signal = new AnomalySignal(true, "강함", "하락",
                java.time.LocalDate.of(2026, 8, 21), new BigDecimal("-4.2"),
                "상위 12퍼센트", "나누어 담으세요.");
        AiInsight insight = new AiInsight("005930", "삼성전자", "문안", Confidence.HIGH, true,
                Optional.empty(), Optional.of(direction), Optional.of(signal),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("88"))), Optional.empty(), Map.of());

        String spoken = insight.fullNarration();
        assertTrue(spoken.startsWith("문안"), spoken);
        assertTrue(spoken.contains("방향 예측은 상승입니다"), spoken);
        assertTrue(spoken.contains("상위 12퍼센트"), spoken);
        assertTrue(spoken.contains("SK하이닉스"), spoken);
        assertTrue(spoken.contains("투자 권유가 아닙니다"), spoken);
    }

    /**
     * 서비스가 쓴 단서가 있으면 그것을 쓴다. 같은 뜻으로 고쳐 쓰면 무엇이 서비스의
     * 주장이고 무엇이 우리 해석인지 사용자가 구별할 수 없다.
     */
    @Test
    @DisplayName("닮은 차트 단서는 서비스가 쓴 문장을 그대로 쓴다")
    void usesTheServiceDisclaimerVerbatim() {
        SimilarOutlook outlook = new SimilarOutlook(5, 3, 2, "표본이 적고 미래를 보장하지 않습니다.",
                "이건 예측이 아닙니다. 닮은 구간 다음에 무슨 일이 있었는지만 셉니다.");
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("88"))),
                Optional.of(outlook), Map.of());

        assertTrue(insight.requiredCaveats().contains(outlook.disclaimer()),
                insight.requiredCaveats().toString());
    }

    /** 서비스가 단서를 안 주면 자리를 비우지 않는다. 단서 없는 닮은 차트가 더 위험하다. */
    @Test
    @DisplayName("서비스 단서가 없으면 우리 문장으로 대신한다")
    void fallsBackToOurOwnDisclaimer() {
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("88"))),
                Optional.empty(), Map.of());

        assertTrue(insight.requiredCaveats().stream()
                .anyMatch(c -> c.contains("앞으로 같이 움직인다는 뜻이 아닙니다")),
                insight.requiredCaveats().toString());
    }

    /**
     * 닮았다는 사실만 말하면 그다음이 궁금해진다. 그 자리를 비워 두면 사용자가 스스로
     * 채운다. 상승 몇 건 · 하락 몇 건은 세어 둔 사실이다.
     */
    @Test
    @DisplayName("닮은 구간 다음에 무슨 일이 있었는지 건수로 말한다")
    void readsOutWhatActuallyHappenedNext() {
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("88"))),
                Optional.of(new SimilarOutlook(5, 3, 2, "", "단서")), Map.of());

        String text = insight.similarText().orElseThrow();
        assertTrue(text.contains("오른 경우 3건"), text);
        assertTrue(text.contains("내린 경우 2건"), text);
    }

    /** 0건 0건은 사실이 아니라 자료 없음이다. 그것을 사실처럼 읽어 주면 안 된다. */
    @Test
    @DisplayName("셀 것이 없으면 건수를 말하지 않는다")
    void staysSilentWhenThereIsNothingToCount() {
        AiInsight insight = new AiInsight("005930", "삼성전자", "문장", Confidence.HIGH, true,
                Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new SimilarStock("000660", "SK하이닉스", new BigDecimal("88"))),
                Optional.of(new SimilarOutlook(5, 0, 0, "", "단서")), Map.of());

        assertFalse(insight.similarText().orElseThrow().contains("건"),
                insight.similarText().orElseThrow());
    }

    /**
     * 모양이 0.98 로 닮았는데 함께 움직인 정도는 0.21 인 짝이 흔하다. 다른 시기의 다른
     * 종목이 우연히 같은 곡선을 그린 경우다. 유사도만 말하면 그 둘이 구별되지 않는다.
     */
    @Test
    @DisplayName("함께 움직인 정도를 받았으면 유사도와 함께 말한다")
    void tellsSimilarityAndComovementApart() {
        SimilarStock stock = new SimilarStock("000660", "SK하이닉스", new BigDecimal("98"),
                Optional.of(new BigDecimal("21")));

        assertEquals("SK하이닉스 유사도 98퍼센트, 함께 움직인 정도 21퍼센트", stock.describe());
    }
}
