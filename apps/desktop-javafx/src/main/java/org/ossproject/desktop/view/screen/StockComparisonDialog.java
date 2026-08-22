package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.chart.CandlestickChartView;
import org.ossproject.finance.model.market.Candle;
import org.ossproject.finance.model.market.PricePoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 두 종목을 나란히 보여 준다.
 *
 * <p>차트와 말을 함께 둔다. 눈으로 보는 사람에게는 두 곡선을 겹쳐 보는 것이 가장 빠르고,
 * 화면을 볼 수 없는 사람에게 두 개의 선은 아무것도 아니다. 그래서 같은 기간 등락을 같은
 * 말로도 적어 둔다. 차트에는 접근성 이름이 붙어 있어 읽어 줄 수 있다.
 *
 * <p>봉을 못 받으면 가격 자리를 비운다. 채워 넣지 않는다 — 화면을 볼 수 없는 사용자는
 * 지어낸 값과 실제 시세를 구별할 방법이 없고, 그 값으로 주문을 낸다.
 */
public final class StockComparisonDialog {

    private static final NumberFormat WON = NumberFormat.getIntegerInstance(Locale.KOREA);

    private StockComparisonDialog() {
    }

    /**
     * @param onAdd 관심 종목에 담기를 눌렀을 때. 누르지 않으면 부르지 않는다
     */
    public static void show(String queryName, List<Candle> queryBars,
                            String otherName, List<Candle> otherBars,
                            BigDecimal similarityPercent, String explanation,
                            Runnable onAdd) {
        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle(queryName + " · " + otherName + " 비교");
        dialog.setHeaderText(null);

        VBox content = new VBox(14, new Label(queryName + " · " + otherName + " 비교"));
        content.setPadding(new Insets(4));

        HBox columns = new HBox(20, column(queryName, queryBars), column(otherName, otherBars));
        columns.setAlignment(Pos.TOP_LEFT);
        content.getChildren().add(columns);

        Label score = new Label("유사도 "
                + similarityPercent.stripTrailingZeros().toPlainString() + "퍼센트");
        score.getStyleClass().add("metric-value");
        VBox similarity = new VBox(6, score);
        if (explanation != null && !explanation.isBlank()) {
            Label detail = new Label(explanation);
            detail.setWrapText(true);
            detail.setMinHeight(Region.USE_PREF_SIZE);
            detail.setMaxWidth(520);
            similarity.getChildren().add(detail);
        }
        similarity.getStyleClass().add("panel-card");
        similarity.setPadding(new Insets(12));
        content.getChildren().add(similarity);

        // 창을 닫고 나면 무엇을 보았는지 남는 것이 없다. 요약이 곧 이 창의 접근성 이름이다.
        content.setAccessibleText(queryName + "와 " + otherName + " 비교. 유사도 "
                + similarityPercent.stripTrailingZeros().toPlainString() + "퍼센트. "
                + "과거 모양이 닮았다는 뜻이며 앞으로 같이 움직인다는 뜻이 아닙니다.");

        ButtonType add = new ButtonType(otherName + " 관심 종목에 추가", ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType("닫기", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(add, close);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(620);

        if (dialog.showAndWait().filter(add::equals).isPresent()) {
            onAdd.run();
        }
    }

    private static VBox column(String name, List<Candle> bars) {
        Label title = new Label(name);
        title.getStyleClass().add("section-title");
        VBox column = new VBox(6, title);
        HBox.setHgrow(column, Priority.ALWAYS);
        column.setMinWidth(240);

        Optional<BigDecimal> last = bars == null || bars.isEmpty()
                ? Optional.empty() : Optional.of(bars.get(bars.size() - 1).close());
        if (last.isEmpty()) {
            // 값이 없으면 없다고 적는다. 빈 칸은 0원으로 읽힌다.
            Label missing = new Label("시세를 받지 못했습니다.");
            column.getChildren().add(missing);
            column.setAccessibleText(name + " 시세를 받지 못했습니다.");
            return column;
        }

        Label price = new Label(WON.format(last.get()) + "원");
        price.getStyleClass().add("metric-value");
        column.getChildren().add(price);

        // 차트는 곁들이는 것이다. 못 그려도 숫자와 말은 그대로 남아야 한다.
        chartOf(bars).ifPresent(column.getChildren()::add);

        changeOverWeek(bars).ifPresent(change -> {
            Label label = new Label(prefixed(change) + "퍼센트");
            label.getStyleClass().add(change.signum() >= 0 ? "price-up" : "price-down");
            column.getChildren().addAll(label, new Label("최근 5거래일"));
        });
        column.setAccessibleText(name + " " + WON.format(last.get()) + "원"
                + changeOverWeek(bars).map(c -> ", 최근 5거래일 " + prefixed(c) + "퍼센트").orElse(""));
        return column;
    }

    /**
     * 봉을 캔들 차트로.
     *
     * <p>같은 그림 도구를 종목 상세와 함께 쓴다. 여기서만 다르게 그리면 같은 종목이 화면에
     * 따라 다르게 보인다.
     */
    private static Optional<CandlestickChartView> chartOf(List<Candle> bars) {
        List<PricePoint> points = new java.util.ArrayList<>();
        for (Candle bar : bars) {
            points.add(bar.toPricePoint(java.time.ZoneId.of("Asia/Seoul")));
        }
        if (points.isEmpty()) {
            return Optional.empty();
        }
        CandlestickChartView chart = new CandlestickChartView(points);
        chart.setPrefHeight(220);
        chart.setMinHeight(200);
        return Optional.of(chart);
    }

    /**
     * 최근 5거래일 등락.
     *
     * <p>봉이 모자라면 계산하지 않는다. 있는 만큼으로 재면 종목마다 기간이 달라져 두
     * 숫자를 나란히 놓는 뜻이 사라진다.
     */
    private static Optional<BigDecimal> changeOverWeek(List<Candle> bars) {
        if (bars == null || bars.size() < 6) {
            return Optional.empty();
        }
        BigDecimal before = bars.get(bars.size() - 6).close();
        BigDecimal now = bars.get(bars.size() - 1).close();
        if (before.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(now.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before, 1, RoundingMode.HALF_UP));
    }

    private static String prefixed(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + value.toPlainString();
    }
}
