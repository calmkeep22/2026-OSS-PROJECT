package org.ossproject.desktop.ai;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.ai.AiInsight;
import org.ossproject.ai.AnomalySignal;
import org.ossproject.ai.Forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * AI 분석 결과를 보여 주는 칸.
 *
 * <p>분석 문장은 서비스가 스크린리더용으로 쓴 것을 그대로 쓴다. 화면이 문장을 새로 지어
 * 내면 숫자를 앞에 두게 되고, 신뢰도가 낮다는 사실이 문장에서 빠진다.
 *
 * <p>함께 전해야 하는 단서는 고를 수 있는 것이 아니라 값이 정해 준다. 전부 적는다.
 */
public final class AiInsightCard {

    /** 한 줄이 이보다 길면 눈이 다음 줄 첫머리를 놓친다. */
    private static final double MAX_READING_WIDTH = 820;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM월 dd일");

    private final Label narration = new Label();
    private final Label direction = new Label();
    private final Label risk = new Label();
    private final Label similar = new Label();
    private final Label caveats = new Label();
    private final Label analysisHeading = sectionHeading("분석 해설");
    private final Label stateBadge = new Label();
    private final Label confidenceBadge = new Label();
    private final FlowPane metrics = new FlowPane(10, 10);
    private final MetricBlock changeMetric = new MetricBlock("오늘 등락");
    private final MetricBlock volatilityMetric = new MetricBlock("변동성 전망");
    private final MetricBlock directionMetric = new MetricBlock("방향 참고");
    private final MetricBlock riskMetric = new MetricBlock("위험도");
    private final VBox directionSection;
    private final VBox riskSection;
    private final VBox similarSection;
    private final VBox caveatSection;
    private final Button listen;
    private final VBox root;

    public AiInsightCard(Consumer<String> onListen) {
        this("AI 분석", onListen);
    }

    /**
     * 제목을 정해 만든다.
     *
     * <p>여러 종목을 한 화면에 늘어놓을 때 쓴다. 카드마다 "AI 분석" 이라고만 적혀 있으면
     * 위에서 아래로 듣는 사용자는 지금 어느 종목 이야기인지 알 수 없다.
     */
    public AiInsightCard(String title, Consumer<String> onListen) {
        Objects.requireNonNull(onListen, "onListen");
        narration.setWrapText(true);
        narration.getStyleClass().addAll("ai-narration", "ai-insight-copy");
        // 방향 예측은 문안과 섞지 않는다. 문안은 변동성 이야기라 한 덩이로 읽으면 어느
        // 확률이 무엇에 대한 것인지 흐려진다.
        direction.setWrapText(true);
        direction.getStyleClass().addAll("ai-narration", "ai-insight-copy");
        // 위험도와 닮은 차트도 문안과 섞지 않는다. 한 덩이가 되면 어느 문장이 사실이고
        // 어느 문장이 참고인지 귀로 가려내기 어렵다.
        for (Label label : new Label[]{risk, similar}) {
            label.setWrapText(true);
            label.getStyleClass().addAll("ai-narration", "ai-insight-copy");
        }
        caveats.setWrapText(true);
        caveats.getStyleClass().add("ai-caveat-copy");

        directionSection = detailSection("방향 예측", direction);
        riskSection = detailSection("위험 관리", risk);
        similarSection = detailSection("과거 유사 구간", similar);
        caveatSection = detailSection("반드시 확인하세요", caveats);
        caveatSection.getStyleClass().add("ai-caveat-section");

        listen = new Button("AI 분석 듣기");
        listen.getStyleClass().add("primary-button");
        listen.setDisable(true);
        listen.setOnAction(event -> onListen.accept(spoken));

        String displayTitle = title == null || title.isBlank() ? "AI 분석" : title;
        Label companyBadge = new Label(companyBadgeText(displayTitle));
        companyBadge.getStyleClass().add("ai-company-badge");
        companyBadge.setAccessibleText(displayTitle + " 회사 배지");
        Label heading = new Label(displayTitle);
        heading.getStyleClass().addAll("card-title", "ai-insight-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox badges = new HBox(7, stateBadge, confidenceBadge);
        badges.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(12, companyBadge, heading, spacer, badges);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("ai-insight-header");

        stateBadge.getStyleClass().add("ai-status-badge");
        confidenceBadge.getStyleClass().add("ai-status-badge");
        metrics.getStyleClass().add("ai-metric-grid");
        metrics.getChildren().addAll(changeMetric.root, volatilityMetric.root,
                directionMetric.root, riskMetric.root);

        root = new VBox(12, header, metrics, analysisHeading, narration, directionSection,
                riskSection, similarSection, caveatSection, listen);
        root.getStyleClass().addAll("panel-card", "ai-insight-card");
        root.setPadding(new Insets(18));
        // 부모가 폭을 잡아 주지 않으면 wrapText 만으로는 줄이 바뀌지 않는다. 라벨이 한 줄로
        // 늘어나 문장 뒤쪽이 잘린다. 잘린 문장은 신뢰도와 면책이 사라진 문장이다.
        bindWrapWidth(narration);
        bindWrapWidth(direction);
        bindWrapWidth(risk);
        bindWrapWidth(similar);
        bindWrapWidth(caveats);
        waiting();
    }

    private static String companyBadgeText(String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isBlank() || "AI 분석".equals(normalized)) return "AI";
        int count = Math.min(2, normalized.codePointCount(0, normalized.length()));
        return normalized.substring(0, normalized.offsetByCodePoints(0, count)).toUpperCase();
    }

    /**
     * 읽기 좋은 폭에서 줄을 바꾼다.
     *
     * <p>카드 폭에만 맞추면 카드가 창보다 넓어졌을 때 문장이 창 밖으로 나가 잘린다. 한 줄이
     * 너무 길어도 눈이 다음 줄 첫머리를 찾기 어렵다. 둘 중 좁은 쪽을 쓴다.
     */
    private void bindWrapWidth(Label label) {
        label.setMinWidth(0);
        label.maxWidthProperty().bind(javafx.beans.binding.Bindings.min(
                root.widthProperty().subtract(32), MAX_READING_WIDTH));
        // 세로 공간이 모자라면 부모가 라벨을 최소 높이로 누른다. 접힌 줄이 있어도 한 줄만
        // 남고 나머지가 잘린다. 접은 높이를 최소로 삼아 눌리지 않게 한다.
        label.setMinHeight(Region.USE_PREF_SIZE);
    }

    private String spoken = "";

    public javafx.scene.Node root() {
        return root;
    }

    public void waiting() {
        showMessage("AI 분석을 기다리고 있습니다.");
        listen.setDisable(true);
    }

    /**
     * 분석 결과를 보여 준다.
     *
     * <p>문안과 단서를 함께 둔다. 읽어 주는 문장도 같은 것을 쓴다. 화면 글자와 음성이
     * 다르면 스크린리더 사용자가 다른 내용을 듣는다.
     */
    public void show(AiInsight insight) {
        setMainNarration(insight.narration());
        configureBadges(insight);
        configureMetrics(insight);
        setSection(directionSection, direction, insight.directionText().orElse(""));
        setSection(riskSection, risk, insight.riskText().orElse(""));
        setSection(similarSection, similar, insight.similarText().orElse(""));

        java.util.List<String> notices = new java.util.ArrayList<>(insight.requiredCaveats());
        insight.partialFailureText().ifPresent(notices::add);
        String caveatText = notices.isEmpty() ? "" : "• " + String.join("\n• ", notices);
        setSection(caveatSection, caveats, caveatText);
        spoken = insight.fullNarration();
        listen.setDisable(false);
    }

    /**
     * 분석을 받지 못한 이유를 적는다.
     *
     * <p>빈 칸으로 두지 않는다. 분석이 없는 것과 "이상 없음" 은 다른 뜻인데, 아무것도
     * 없으면 사용자는 그 종목에 문제가 없다고 읽는다.
     */
    public void unavailable(String reason) {
        showMessage(reason == null || reason.isBlank() ? "AI 분석을 사용할 수 없습니다." : reason);
        stateBadge.setText("사용 불가");
        stateBadge.getStyleClass().removeAll("ai-status-normal", "ai-status-alert");
        stateBadge.getStyleClass().add("ai-status-unavailable");
        setVisible(stateBadge, true);
        listen.setDisable(true);
    }

    /** 값이 없으면 구역 자체를 없앤다. 빈 제목은 스크린리더가 읽을 내용이 없다. */
    private static void setSection(VBox section, Label label, String text) {
        boolean has = !text.isBlank();
        label.setText(text);
        label.setAccessibleText(text);
        setVisible(section, has);
    }

    private void setMainNarration(String main) {
        narration.setText(main);
        narration.setAccessibleText(main);
        spoken = main;
        setVisible(analysisHeading, true);
    }

    private void showMessage(String message) {
        setMainNarration(message);
        setVisible(analysisHeading, false);
        setVisible(metrics, false);
        setVisible(stateBadge, false);
        setVisible(confidenceBadge, false);
        setSection(directionSection, direction, "");
        setSection(riskSection, risk, "");
        setSection(similarSection, similar, "");
        setSection(caveatSection, caveats, "");
    }

    private void configureBadges(AiInsight insight) {
        boolean unusual = insight.anomaly().map(AnomalySignal::unusual).orElse(false);
        stateBadge.setText(unusual ? "평소와 다른 움직임" : "정상 범위");
        stateBadge.setAccessibleText("현재 상태 " + stateBadge.getText());
        stateBadge.getStyleClass().removeAll("ai-status-normal", "ai-status-alert",
                "ai-status-unavailable");
        stateBadge.getStyleClass().add(unusual ? "ai-status-alert" : "ai-status-normal");
        setVisible(stateBadge, true);

        confidenceBadge.setText("신뢰도 " + insight.confidence().displayName());
        confidenceBadge.setAccessibleText("분석 " + confidenceBadge.getText());
        confidenceBadge.getStyleClass().removeAll("ai-confidence-ok", "ai-confidence-warning");
        confidenceBadge.getStyleClass().add(insight.confidence().needsWarning()
                ? "ai-confidence-warning" : "ai-confidence-ok");
        setVisible(confidenceBadge, true);
    }

    private void configureMetrics(AiInsight insight) {
        AnomalySignal anomaly = insight.anomaly().orElse(null);
        if (anomaly == null) {
            changeMetric.update("자료 없음", "등락 정보 없음");
            riskMetric.update("자료 없음", "위험도 정보 없음");
        } else {
            String change = anomaly.changePercent() == null ? "자료 없음"
                    : signedPercent(anomaly.changePercent());
            String changeDetail = String.join(" · ", java.util.stream.Stream.of(
                            anomaly.direction(), anomaly.observedOn() == null ? ""
                                    : SHORT_DATE.format(anomaly.observedOn()))
                    .filter(value -> value != null && !value.isBlank()).toList());
            changeMetric.update(change, changeDetail.isBlank() ? "당일 움직임" : changeDetail);
            riskMetric.update(anomaly.riskGrade().isBlank() ? "자료 없음" : anomaly.riskGrade(),
                    anomaly.riskGrade().isBlank() ? "위험도 정보 없음" : "종목 비교군 기준");
        }

        configureForecast(volatilityMetric, insight.forecast().orElse(null), false);
        configureForecast(directionMetric, insight.directionForecast().orElse(null), true);
        setVisible(metrics, true);
    }

    private static void configureForecast(MetricBlock metric, Forecast forecast,
                                          boolean directionForecast) {
        if (forecast == null) {
            metric.update("제공 안 됨", "예측 정보 없음");
            return;
        }
        String detail = "확률 " + percent(forecast.probability()) + " · " + forecast.sessionText();
        if (directionForecast && !forecast.meaningful()) {
            detail = "참고용 · " + detail;
        }
        metric.update(readableVerdict(forecast.verdict()), detail);
    }

    private static String readableVerdict(String verdict) {
        return switch (verdict.replace(" ", "")) {
            case "크게움직임" -> "큰 움직임";
            case "잔잔함", "잔잔" -> "잔잔한 움직임";
            default -> verdict;
        };
    }

    private static String signedPercent(BigDecimal value) {
        String number = value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return (value.signum() > 0 ? "+" : "") + number + "%";
    }

    private static String percent(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private static VBox detailSection(String title, Label content) {
        VBox section = new VBox(6, sectionHeading(title), content);
        section.getStyleClass().add("ai-insight-section");
        setVisible(section, false);
        return section;
    }

    private static Label sectionHeading(String text) {
        Label heading = new Label(text);
        heading.getStyleClass().add("ai-insight-section-title");
        return heading;
    }

    private static void setVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static final class MetricBlock {
        private final Label value = new Label();
        private final Label detail = new Label();
        private final VBox root;

        private MetricBlock(String title) {
            Label label = new Label(title);
            label.getStyleClass().add("ai-metric-label");
            value.getStyleClass().add("ai-metric-value");
            detail.getStyleClass().add("ai-metric-detail");
            detail.setWrapText(true);
            root = new VBox(4, label, value, detail);
            root.getStyleClass().add("ai-metric-card");
            root.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        }

        private void update(String valueText, String detailText) {
            value.setText(valueText);
            detail.setText(detailText);
            root.setAccessibleText(valueText + ". " + detailText);
        }
    }
}
