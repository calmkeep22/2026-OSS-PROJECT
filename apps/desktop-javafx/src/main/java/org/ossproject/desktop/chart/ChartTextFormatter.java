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

    public String summary(String stockName, GraphSummary summary) {
        String trend = switch (summary.trend()) {
            case RISING -> "전체적으로 상승";
            case FALLING -> "전체적으로 하락";
            case FLAT -> "전체적으로 보합";
        };
        String largestDirection = summary.largestStepPercent() >= 0 ? "상승" : "하락";
        return stockName + " 최근 1개월 차트입니다. " + summary.pointCount() + "개 종가 지점이며, "
                + trend + "하여 첫 종가 대비 " + percent(summary.totalChangePercent())
                + "퍼센트 변했습니다. 최저가는 " + date(summary.minimum()) + " "
                + won(summary.minimum().value()) + ", 최고가는 " + date(summary.maximum()) + " "
                + won(summary.maximum().value()) + "입니다. 가장 큰 지점 간 변화는 "
                + date(summary.largestStepEnd()) + "의 " + largestDirection + " "
                + percent(summary.largestStepPercent()) + "퍼센트입니다.";
    }

    public String exactPoint(TimeSeriesSample sample, double referenceValue) {
        double change = (sample.value() - referenceValue) / referenceValue * 100.0;
        String direction = change > 0 ? "상승" : change < 0 ? "하락" : "동일";
        return date(sample) + ", 종가 " + won(sample.value()) + ", 첫 종가 대비 "
                + direction + " " + percent(change) + "퍼센트입니다.";
    }

    public String pointLabel(int index, int total, TimeSeriesSample sample, double referenceValue) {
        double change = (sample.value() - referenceValue) / referenceValue * 100.0;
        String direction = change > 0 ? "상승" : change < 0 ? "하락" : "기준";
        return (index + 1) + "/" + total + " · " + date(sample) + " · " + won(sample.value())
                + " · " + direction + " " + percent(change) + "%";
    }

    public String playbackPoint(int index, int total, TimeSeriesSample sample, GraphAudioFrame frame) {
        return (index + 1) + "/" + total + " 지점 · " + date(sample) + " · "
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

    public String date(TimeSeriesSample sample) {
        var date = sample.timestamp().atZone(zoneId).toLocalDate();
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일";
    }

    private static String won(double value) {
        return Formatters.won(BigDecimal.valueOf(value));
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f", Math.abs(value));
    }
}
