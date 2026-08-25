package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.finance.model.PriceDirection;
import org.ossproject.finance.model.market.StockDetail;

import java.math.BigDecimal;
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

        HBox summary = summaryRow();
        summary.getStyleClass().addAll("panel-card", "stock-detail-summary");

        // 호가는 한 줄의 세로 공간도 실제 호가 단계 수로 이어진다. 상단 검색바와 호가
        // 자체 접근성 이름에 이미 종목 맥락이 있으므로 호가 탭에서만 중복 요약을 감춘다.
        // managed 도 함께 꺼야 보이지 않는 카드의 자리까지 탭이 온전히 가져간다.
        tabs.getSelectionModel().selectedIndexProperty().addListener((observable, old, selected) -> {
            boolean showSummary = selected == null || selected.intValue() != 1;
            summary.setVisible(showSummary);
            summary.setManaged(showSummary);
        });

        VBox body = new VBox(12, summary, tabs);
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0);
        body.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        body.getStyleClass().add("screen-content");
        body.setAccessibleText("종목 상세 " + detail.name());
        body.setAccessibleHelp(
                "Alt+B 매수, Alt+Shift+B 매도, Alt+W 관심종목 전환, Control+Tab 다음 탭");
        body.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isAltDown() && event.getCode() == KeyCode.B) {
                if (event.isShiftDown()) onSell.run();
                else onBuy.run();
                event.consume();
            } else if (event.isAltDown() && !event.isShiftDown() && event.getCode() == KeyCode.W) {
                watchlistToggle.fire();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.TAB) {
                cycleTab(tabs, event.isShiftDown());
                event.consume();
            }
        });
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return body;
    }

    private static void cycleTab(TabPane tabs, boolean backwards) {
        int count = tabs.getTabs().size();
        if (count == 0) return;
        int current = Math.max(0, tabs.getSelectionModel().getSelectedIndex());
        int next = backwards ? (current - 1 + count) % count : (current + 1) % count;
        tabs.getSelectionModel().select(next);
    }

    /**
     * 종목·현재가·고저·주문을 한 줄에 담는다.
     *
     * <p>두 줄로 나눠 두었더니 요약 카드가 세로를 두 배로 먹고 차트가 그만큼 눌렸다.
     * 값 자체는 짧아 한 줄에 들어간다 — 고가와 저가는 두 줄짜리 작은 카드 대신 한 줄
     * 글로 적고, 시가와 거래량은 뺐다.
     *
     * <p>창이 좁아지거나 큰 글자 모드가 켜지면 왼쪽 묶음이 알아서 접힌다. 접히더라도
     * 매도·매수는 오른쪽 끝에 남는다. 주문 단추가 접혀 사라지면 안 된다.
     */
    private HBox summaryRow() {
        Label title = heading(detail.name());
        title.getStyleClass().add("stock-detail-title");
        Label symbol = new Label(detail.symbol() + " · " + exchange);
        symbol.getStyleClass().addAll("mode-badge", "stock-detail-symbol");

        // 등락은 비율만 적는다. 금액은 현재가와 전일 종가에서 곧 나오는 값이라, 한 줄에
        // 담기 위해 줄인다면 여기가 먼저다. 읽어 주는 문장도 원래 비율만 말한다.
        Label price = new Label(formatPrice.apply(detail.currentPrice()) + " · " + directionText()
                + " " + detail.changeRate().abs() + "%");
        price.getStyleClass().add("stock-price");
        price.setAccessibleText(detail.name() + " 현재가 " + formatPrice.apply(detail.currentPrice())
                + ", 전일 대비 " + directionText() + " " + formatPrice.apply(detail.changeAmount().abs())
                + ", " + detail.changeRate().abs() + "퍼센트");

        // 시가와 거래량은 뺐다. 같은 값이 차트의 "접근 가능한 표" 와 체결 탭에 그대로
        // 있어 잃는 것이 없다.
        Label extremes = new Label("고가 " + formatPrice.apply(detail.high())
                + " · 저가 " + formatPrice.apply(detail.low()));
        extremes.getStyleClass().add("stock-extremes");
        extremes.setAccessibleText("오늘 고가 " + formatPrice.apply(detail.high())
                + ", 저가 " + formatPrice.apply(detail.low()));

        Button listen = new Button("최신 정보 듣기");
        listen.getStyleClass().add("stock-compact-action");
        listen.setOnAction(event -> speak.accept(spokenSummary(), "stock-detail-" + detail.symbol()));

        watchlistToggle.getStyleClass().add("stock-compact-action");
        Button buy = primaryButton("매수", onBuy);
        buy.getStyleClass().add("stock-compact-action");
        Button sell = new Button("매도");
        sell.getStyleClass().addAll("sell-button", "stock-compact-action");
        sell.setOnAction(event -> onSell.run());

        FlowPane info = wrappingRow(8, title, symbol, price, extremes, listen);
        info.getStyleClass().add("stock-quote-row");
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        // 주문 단추는 한 덩어리로 묶어 절대 줄지 않게 못 박는다. 낱개로 두었더니 값이
        // 긴 종목에서 오른쪽 끝의 매수가 카드 밖으로 밀려 눌릴 수 없었다.
        // 자리가 모자라면 줄어드는 쪽은 언제나 왼쪽 묶음이어야 한다 — 거기 있는 값은
        // 접혀도 읽히지만, 화면 밖으로 나간 단추는 누를 방법이 없다.
        HBox actions = new HBox(8, watchlistToggle, sell, buy);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.setFillHeight(false);

        HBox row = new HBox(8, info, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
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
