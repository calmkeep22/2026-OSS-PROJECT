package org.ossproject.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 한 종목에 대한 AI 요약.
 *
 * <p>예측과 이상감지와 유사종목을 합친 결과다. 셋을 따로 받아 화면에서 합치면 합치는
 * 방식이 화면마다 달라진다. 어느 것을 먼저 읽을지, 하나가 실패하면 전체를 실패로 볼지가
 * 전부 재량이 된다.
 *
 * <p>{@code narration} 은 스크린리더로 읽으라고 쓴 문장이다. 숫자를 앞에 두지 않고,
 * 신뢰도가 낮으면 그 사실이 문장 안에 들어 있다. 화면이 문장을 새로 지어내지 말고 이것을
 * 그대로 쓴다.
 *
 * @param forecast          기본 예측. 다음 거래일에 크게 움직일지 잔잔할지
 * @param directionForecast 오를지 내릴지. 검증에서 우연과 구별되지 않아 함께 알려야 한다
 * @param similarOutlook    닮은 구간 다음에 실제로 무슨 일이 있었는지와 서비스가 쓴 단서
 * @param partialFailures   일부만 실패했을 때 무엇이 실패했는지. 전체를 실패로 만들지 않는다
 */
public record AiInsight(
        String symbol,
        String name,
        String narration,
        Confidence confidence,
        boolean statisticallyMeaningful,
        Optional<Forecast> forecast,
        Optional<Forecast> directionForecast,
        Optional<AnomalySignal> anomaly,
        List<SimilarStock> similar,
        Optional<SimilarOutlook> similarOutlook,
        Map<String, String> partialFailures
) {
    public AiInsight {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("종목 코드는 필수입니다.");
        }
        if (narration == null || narration.isBlank()) {
            throw new IllegalArgumentException("읽어 줄 문장은 필수입니다.");
        }
        name = name == null || name.isBlank() ? symbol : name;
        confidence = confidence == null ? Confidence.UNKNOWN : confidence;
        forecast = forecast == null ? Optional.empty() : forecast;
        directionForecast = directionForecast == null ? Optional.empty() : directionForecast;
        anomaly = anomaly == null ? Optional.empty() : anomaly;
        similar = List.copyOf(similar == null ? List.of() : similar);
        similarOutlook = similarOutlook == null ? Optional.empty() : similarOutlook;
        partialFailures = Map.copyOf(partialFailures == null ? Map.of() : partialFailures);
    }

    /**
     * 이 결과와 반드시 함께 전해야 하는 단서.
     *
     * <p>화면이 고를 수 있는 목록이 아니다. 전부 읽거나 보여 주어야 한다. 숫자만 전하면
     * 화면을 볼 수 없는 사용자는 그 숫자를 얼마나 믿어야 하는지 알 방법이 없다.
     */
    public List<String> requiredCaveats() {
        List<String> caveats = new ArrayList<>();
        if (confidence.needsWarning()) {
            caveats.add("이 분석의 신뢰도는 " + confidence.displayName() + "입니다. 참고용으로만 보세요.");
        }
        if (!statisticallyMeaningful) {
            caveats.add("이 예측은 검증에서 우연과 구별되지 않았습니다. 판단 근거로 삼지 마세요.");
        }
        // 방향 예측은 검증에서 우연과 구별되지 않았다. 차익거래로 지워지기 때문이다.
        // 누가 내일 오를 것을 알면 오늘 산다. 이 사실을 빼면 동전 던지기를 신호로 읽는다.
        if (directionForecast.isPresent() && !directionForecast.get().meaningful()) {
            caveats.add("오를지 내릴지에 대한 예측은 검증에서 우연과 구별되지 않았습니다. "
                    + "참고만 하고 판단 근거로 삼지 마세요.");
        }
        if (!similar.isEmpty()) {
            // 서비스가 쓴 문장이 있으면 그것을 쓴다. 같은 뜻으로 고쳐 쓰면 무엇이 서비스의
            // 주장이고 무엇이 우리 해석인지 사용자가 구별할 수 없게 된다.
            caveats.add(similarOutlook.map(SimilarOutlook::disclaimer)
                    .filter(text -> !text.isBlank())
                    .orElse("닮은 차트는 과거 모양이 비슷하다는 뜻일 뿐, "
                            + "앞으로 같이 움직인다는 뜻이 아닙니다."));
        }
        caveats.add("투자 권유가 아닙니다. 이 분석은 다음 거래일 하루만 봅니다.");
        return List.copyOf(caveats);
    }

    /**
     * 방향 예측을 읽어 줄 문장. 받지 못했으면 비어 있다.
     *
     * <p>없을 때 빈 문장을 지어내지 않는다. 화면을 볼 수 없는 사용자는 지어낸 문장과 실제
     * 예측을 구별할 방법이 없다.
     */
    public Optional<String> directionText() {
        return directionForecast.map(Forecast::narration);
    }

    /**
     * 이 종목을 다루는 방법에 대한 문장. 위험도와 그에 딸린 조언이다.
     *
     * <p>문안에는 오늘 무슨 일이 있었는지만 들어 있고 위험도는 빠져 있다. 같은 이상
     * 신호라도 평소 크게 흔들리는 종목과 잔잔한 종목은 뜻이 다르다.
     */
    public Optional<String> riskText() {
        return anomaly.flatMap(signal -> {
            String grade = signal.riskGrade();
            String advice = signal.riskAdvice();
            if (grade.isBlank() && advice.isBlank()) {
                return Optional.empty();
            }
            String head = grade.isBlank() ? "" : "이 종목의 위험도는 " + grade + "입니다.";
            return Optional.of((head + " " + advice).trim());
        });
    }

    /**
     * 닮은 종목을 모두 읽어 줄 문장.
     *
     * <p>문안은 가장 닮은 하나만 말한다. 하나만 들으면 그 종목이 특별해 보이는데, 실제로는
     * 비슷한 후보가 여럿이고 그중 첫째일 뿐이다.
     */
    public Optional<String> similarText() {
        if (similar.isEmpty()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        for (SimilarStock stock : similar) {
            parts.add(stock.describe());
        }
        String text = "닮은 차트: " + String.join(", ", parts) + ".";
        // 닮았다는 사실만 말하면 그다음이 궁금해진다. 그 자리를 비워 두면 사용자가
        // 스스로 채운다. 실제로 무슨 일이 있었는지는 세어 둔 값이 있다.
        return Optional.of(similarOutlook.filter(SimilarOutlook::hasCounts)
                .map(outlook -> text + " " + outlook.describe())
                .orElse(text));
    }

    /** 화면과 음성이 함께 쓰는 전체 문장. 문안과 곁들일 사실 뒤에 단서를 잇는다. */
    public String fullNarration() {
        StringBuilder sb = new StringBuilder(narration);
        directionText().ifPresent(text -> sb.append(' ').append(text));
        riskText().ifPresent(text -> sb.append(' ').append(text));
        similarText().ifPresent(text -> sb.append(' ').append(text));
        for (String caveat : requiredCaveats()) {
            sb.append(' ').append(caveat);
        }
        return sb.toString();
    }

    /** 일부가 실패했는지. 실패한 것을 조용히 빼지 않고 알린다. */
    public boolean hasPartialFailure() {
        return !partialFailures.isEmpty();
    }

    /** 실패한 항목을 읽어 줄 문장으로. */
    public Optional<String> partialFailureText() {
        if (partialFailures.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> ordered = new LinkedHashMap<>(partialFailures);
        return Optional.of("일부 분석을 받지 못했습니다. " + String.join(", ", ordered.keySet()) + ".");
    }

    public static AiInsight of(String symbol, String name, String narration, Confidence confidence,
                               boolean meaningful) {
        return new AiInsight(symbol, name, narration, confidence, meaningful,
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
                Optional.empty(), Map.of());
    }

    @Override
    public String toString() {
        return "AiInsight[" + symbol + " " + Objects.toString(name, "") + "]";
    }
}
