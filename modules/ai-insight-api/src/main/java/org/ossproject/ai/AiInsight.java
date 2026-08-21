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
 * @param partialFailures 일부만 실패했을 때 무엇이 실패했는지. 전체를 실패로 만들지 않는다
 */
public record AiInsight(
        String symbol,
        String name,
        String narration,
        Confidence confidence,
        boolean statisticallyMeaningful,
        Optional<Forecast> forecast,
        Optional<AnomalySignal> anomaly,
        List<SimilarStock> similar,
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
        anomaly = anomaly == null ? Optional.empty() : anomaly;
        similar = List.copyOf(similar == null ? List.of() : similar);
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
        if (!similar.isEmpty()) {
            caveats.add("닮은 차트는 과거 모양이 비슷하다는 뜻일 뿐, 앞으로 같이 움직인다는 뜻이 아닙니다.");
        }
        caveats.add("투자 권유가 아닙니다. 이 분석은 다음 거래일 하루만 봅니다.");
        return List.copyOf(caveats);
    }

    /** 화면과 음성이 함께 쓰는 전체 문장. 문안 뒤에 단서를 잇는다. */
    public String fullNarration() {
        StringBuilder sb = new StringBuilder(narration);
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
                Optional.empty(), Optional.empty(), List.of(), Map.of());
    }

    @Override
    public String toString() {
        return "AiInsight[" + symbol + " " + Objects.toString(name, "") + "]";
    }
}
