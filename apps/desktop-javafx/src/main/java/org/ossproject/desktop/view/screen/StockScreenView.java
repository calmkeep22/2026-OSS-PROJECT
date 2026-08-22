package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.market.StockDetail;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 종목 상세 화면. 이미 만들어진 칸들을 받아 배치만 한다.
 *
 * <p>차트와 호가와 체결은 각자 클래스가 있다. 여기서 함께 만들면 이 화면이 셋의 사정을
 * 모두 알아야 하고, 하나를 고칠 때마다 여기도 고쳐야 한다.
 *
 * <p>시가총액과 외국인 소진률 같은 값은 항목 자체를 두지 않는다. 조회 TR 은 함께 주지만
 * 아직 도메인 모델에 담지 않았다. 빈 칸을 두면 0 으로 읽힌다.
 */
public final class StockScreenView {

    private final StockDetail detail;
    private final String exchange;
    private final Function<BigDecimal, String> formatPrice;
    /** 관심종목 담기·빼기 단추. 담긴 상태를 스스로 든다. */
    private final Button watchlistToggle;
    private final Runnable onBuy;
    private final Runnable onSell;
    /** 읽어 줄 문장과 어느 줄에 넣을지. 음성 대기열은 앱이 관리한다. */
    private final BiConsumer<String, String> speak;

    public StockScreenView(StockDetail detail, String exchange,
                           Function<BigDecimal, String> formatPrice, Button watchlistToggle,
                           Runnable onBuy, Runnable onSell, BiConsumer<String, String> speak) {
        this.detail = Objects.requireNonNull(detail, "detail");
        this.exchange = exchange == null ? "" : exchange;
        this.formatPrice = Objects.requireNonNull(formatPrice, "formatPrice");
        this.watchlistToggle = Objects.requireNonNull(watchlistToggle, "watchlistToggle");
        this.onBuy = Objects.requireNonNull(onBuy, "onBuy");
        this.onSell = Objects.requireNonNull(onSell, "onSell");
        this.speak = Objects.requireNonNull(speak, "speak");
    }

    /**
     * @param chart     차트 칸
     * @param orderBook 호가 칸
     * @param trades    체결 칸
     */
    public VBox create(Node chart, Node orderBook, Node trades) {
        TabPane tabs = new TabPane(tab("차트", chart), tab("호가", orderBook), tab("체결", trades));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinHeight(0);
        tabs.setPrefHeight(540);
        tabs.setMaxHeight(Double.MAX_VALUE);
        tabs.getStyleClass().add("stock-detail-tabs");
        tabs.setAccessibleText(detail.name() + " 차트, 호가, 체결 탭");

        VBox summary = new VBox(8, titleRow(), quoteRow());
        summary.getStyleClass().addAll("panel-card", "stock-detail-summary");

        VBox body = new VBox(12, summary, tabs);
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0);
        body.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        body.getStyleClass().add("screen-content");
        body.setAccessibleText("종목 상세 " + detail.name());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return body;
    }

    private HBox titleRow() {
        Label title = heading(detail.name());
        title.getStyleClass().add("stock-detail-title");
        Label symbol = new Label(detail.symbol() + " · " + exchange);
        symbol.getStyleClass().addAll("mode-badge", "stock-detail-symbol");

        watchlistToggle.getStyleClass().add("stock-compact-action");
        Button buy = primaryButton("매수", onBuy);
        buy.getStyleClass().add("stock-compact-action");
        Button sell = new Button("매도");
        sell.getStyleClass().addAll("sell-button", "stock-compact-action");
        sell.setOnAction(event -> onSell.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, title, symbol, spacer, watchlistToggle, sell, buy);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private FlowPane quoteRow() {
        Label price = new Label(formatPrice.apply(detail.currentPrice()) + " · " + directionText()
                + " " + formatPrice.apply(detail.changeAmount().abs())
                + " · " + detail.changeRate().abs() + "%");
        price.getStyleClass().add("stock-price");

        Button listen = new Button("최신 정보 듣기");
        listen.getStyleClass().add("stock-compact-action");
        listen.setOnAction(event -> speak.accept(spokenSummary(), "stock-detail-" + detail.symbol()));

        List<VBox> metrics = List.of(
                miniMetric("시가", formatPrice.apply(detail.open())),
                miniMetric("고가", formatPrice.apply(detail.high())),
                miniMetric("저가", formatPrice.apply(detail.low())),
                miniMetric("거래량", String.format("%,d", detail.volume())));
        metrics.forEach(metric -> metric.setPrefWidth(108));

        FlowPane row = wrappingRow(8, price, listen, metrics.get(0), metrics.get(1),
                metrics.get(2), metrics.get(3));
        row.getStyleClass().add("stock-quote-row");
        return row;
    }

    /**
     * 읽어 줄 문장.
     *
     * <p>등락을 색이 아니라 말로 전한다. 빨강과 파랑만으로는 화면을 볼 수 없는 사용자에게
     * 아무것도 전달되지 않는다.
     */
    private String spokenSummary() {
        return detail.name() + " 현재가 " + formatPrice.apply(detail.currentPrice())
                + ", 전일 대비 " + directionText() + " " + detail.changeRate().abs() + "퍼센트입니다.";
    }

    private String directionText() {
        return switch (detail.direction()) {
            case UP -> "상승";
            case DOWN -> "하락";
            default -> "보합";
        };
    }
}
