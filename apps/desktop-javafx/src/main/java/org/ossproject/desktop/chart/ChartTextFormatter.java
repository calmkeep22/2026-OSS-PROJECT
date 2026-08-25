package org.ossproject.desktop.chart;

import org.ossproject.desktop.presentation.Formatters;
import org.ossproject.sonification.model.GraphAudioFrame;
import org.ossproject.sonification.model.GraphScaleMode;
import org.ossproject.sonification.model.GraphSummary;
import org.ossproject.sonification.model.TimeSeriesSample;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;

/** Produces the visible and spoken Korean descriptions for the accessible chart. */
public final class ChartTextFormatter {
    private final ZoneId zoneId;

    public ChartTextFormatter(ZoneId zoneId) {
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    public String summary(String stockName, GraphSummary summary, String seriesDescription) {
        String trend = switch (summary.trend()) {
            case RISING -> "전체적으로 상승";
            case FALLING -> "전체적으로 하락";
            case FLAT -> "전체적으로 보합";
        };
        String largestDirection = summary.largestStepPercent() >= 0 ? "상승" : "하락";
        // 구간을 말로 박아 두지 않는다. 실제로 "최근 1개월" 이라고 적혀 있었는데 일봉은
        // 250개, 열세 달치였다. 무엇을 듣고 있는지 부르는 쪽이 알려 준다.
        return stockName + " " + seriesDescription + " 차트입니다. " + summary.pointCount() + "개 종가 지점이며, "
                + trend + "하여 첫 종가 대비 " + percent(summary.totalChangePercent())
                + "퍼센트 변했습니다. 최저가는 " + date(summary.minimum()) + " "
                + won(summary.minimum().value()) + ", 최고가는 " + date(summary.maximum()) + " "
                + won(summary.maximum().value()) + "입니다. 가장 큰 지점 간 변화는 "
                + date(summary.largestStepEnd()) + "의 " + largestDirection + " "
                + percent(summary.largestStepPercent()) + "퍼센트입니다.";
    }

    public String exactPoint(TimeSeriesSample sample, double referenceValue, boolean includeTime) {
        double change = (sample.value() - referenceValue) / referenceValue * 100.0;
        String direction = change > 0 ? "상승" : change < 0 ? "하락" : "동일";
        return date(sample, includeTime) + ", 종가 " + won(sample.value()) + ", 첫 종가 대비 "
                + direction + " " + percent(change) + "퍼센트입니다.";
    }

    public String pointLabel(int index, int total, TimeSeriesSample sample, double referenceValue,
                             boolean includeTime) {
        double change = (sample.value() - referenceValue) / referenceValue * 100.0;
        String direction = change > 0 ? "상승" : change < 0 ? "하락" : "기준";
        return (index + 1) + "/" + total + " · " + date(sample, includeTime) + " · " + won(sample.value())
                + " · " + direction + " " + percent(change) + "%";
    }

    public String playbackPoint(int index, int total, TimeSeriesSample sample, GraphAudioFrame frame,
                                boolean includeTime) {
        return (index + 1) + "/" + total + " 지점 · " + date(sample, includeTime) + " · "
                + won(sample.value()) + " · " + Math.round(frame.toFrequencyHz()) + "헤르츠";
    }

    public String liveFrame(String stockName, GraphAudioFrame frame) {
        String direction = frame.percentFromReference() > 0 ? "기준가보다 상승"
                : frame.percentFromReference() < 0 ? "기준가보다 하락" : "기준가와 동일";
        return stockName + ", " + won(frame.currentValue()) + ", " + direction + " "
                + percent(frame.percentFromReference()) + "퍼센트, 음높이 "
                + Math.round(frame.toFrequencyHz()) + "헤르츠";
    }

    public String scaleDescription(GraphScaleMode mode, double percentRange) {
        if (mode == GraphScaleMode.AUTOMATIC) {
            return "자동 범위 · 선택 기간의 최저가를 220헤르츠, 최고가를 880헤르츠로 표현해 그래프 모양을 선명하게 들려줍니다.";
        }
        return "고정 범위 · 첫 종가를 440헤르츠로 두고 ±" + percentRange
                + "퍼센트를 220~880헤르츠로 표현해 실제 등락 크기를 비교합니다.";
    }

    /**
     * 지점의 날짜.
     *
     * <p>올해가 아니면 연도를 붙인다. 일봉 구간이 한 해를 넘기면서 "8월 26일" 이 작년
     * 것인지 올해 것인지 구분되지 않았다. 오늘보다 뒤인 날짜처럼 읽혀 값을 지어낸
     * 것으로 보였다. 소리로만 듣는 사용자는 앞뒤 지점과 견줘 볼 수도 없다.
     *
     * <p>올해 것에는 붙이지 않는다. 대부분의 지점에 "2026년" 이 붙으면 목록을 훑을 때
     * 같은 말이 되풀이돼 정작 다른 부분이 묻힌다.
     */
    public String date(TimeSeriesSample sample) {
        var date = sample.timestamp().atZone(zoneId).toLocalDate();
        String year = date.getYear() == java.time.LocalDate.now(zoneId).getYear()
                ? "" : date.getYear() + "년 ";
        return year + date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
    }

    private String date(TimeSeriesSample sample, boolean includeTime) {
        String date = date(sample);
        if (!includeTime) return date;
        var time = sample.timestamp().atZone(zoneId).toLocalTime();
        return date + " " + String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    private static String won(double value) {
        return Formatters.won(BigDecimal.valueOf(value));
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f", Math.abs(value));
    }
}
