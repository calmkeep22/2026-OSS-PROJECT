package org.ossproject.desktop.orderbook;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.TableCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.view.UiKit;
import org.ossproject.finance.model.PriceLadderRow;
import org.ossproject.finance.model.PriceLadderView;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * 고정 가격 축 호가창.
 *
 * <p>표 하나로 보이는 사용자와 스크린리더 사용자를 모두 감당한다. 그래프와 표를 따로 두면
 * 두 표현이 어긋날 수 있고, 호가창은 원래 숫자 표라 표가 곧 원본이다.
 *
 * <p>가격 축이 옮겨지면 그 사실을 문장으로 알린다. 축이 조용히 미끄러지면 화면을 확대해
 * 보는 사용자는 자기가 보던 가격대가 어디로 갔는지 알 수 없다.
 */
public final class OrderBookLadderView {

    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.KOREA);

    /** 한 행 높이. CSS 의 {@code .order-book-panel .table-row-cell} 과 맞춘다. */
    private static final double ROW_HEIGHT = 34;
    private static final double HEADER_HEIGHT = 32;

    private final ObservableList<PriceLadderRow> rows = FXCollections.observableArrayList();
    private final TableView<PriceLadderRow> table;
    private final Label summary = new Label("호가를 기다리고 있습니다.");
    private final Label announcement = new Label();
    private final Label walls = new Label();
    private final VBox root;
    private boolean live = true;
    private boolean tradedCenter = true;

    public OrderBookLadderView(String stockName) {
        Objects.requireNonNull(stockName, "stockName");
        TableColumn<PriceLadderRow, PriceLadderRow> askColumn = barColumn("매도 잔량", true);
        TableColumn<PriceLadderRow, String> priceColumn =
                UiKit.textColumn("가격", this::priceLabel);
        priceColumn.setStyle("-fx-alignment: CENTER;");
        TableColumn<PriceLadderRow, PriceLadderRow> bidColumn = barColumn("매수 잔량", false);
        table = new TableView<>(rows);
        table.setAccessibleText(stockName + " 호가창 표");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().setAll(List.of(askColumn, priceColumn, bidColumn));
        table.setAccessibleHelp("위아래 방향키로 가격대를 이동합니다. 각 행은 가격과 매도·매수 잔량입니다.");
        // 행 높이를 고정해 두어야 표 전체 높이를 미리 셀 수 있다.
        table.setFixedCellSize(ROW_HEIGHT);
        resizeToRows(0);
        // 행마다 읽어 줄 문장을 도메인이 만들어 준다. 매도·매수를 색이 아니라 말로 구분한다.
        table.setRowFactory(view -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(PriceLadderRow item, boolean empty) {
                super.updateItem(item, empty);
                setAccessibleText(empty || item == null ? null : item.describe());
                pseudoClassStateChanged(CURRENT, item != null && item.currentPriceRow());
            }
        });

        summary.setWrapText(true);
        announcement.setWrapText(true);
        announcement.getStyleClass().add("safety-note");
        announcement.setVisible(false);
        announcement.setManaged(false);

        walls.setWrapText(true);
        walls.getStyleClass().add("safety-note");
        walls.setVisible(false);
        walls.setManaged(false);

        root = new VBox(10, summary, announcement, walls, table);
        root.setPadding(new Insets(12));
        root.setMinSize(0, 0);
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    /**
     * 잔량을 막대와 숫자로 함께 보여 주는 칸.
     *
     * <p>막대 길이는 도메인이 정한 비율을 그대로 쓴다. 잔량이 있으면 최소 길이를 보장하는
     * 규칙도 도메인에 있어서, 편차가 큰 값이 실 한 가닥으로 그려져 "없는 것" 과 헷갈리는
     * 일이 없다.
     *
     * <p>매도는 가운데(가격)를 향해 왼쪽으로, 매수는 오른쪽으로 자란다. 실제 호가창의
     * 읽는 방향과 같다.
     */
    private TableColumn<PriceLadderRow, PriceLadderRow> barColumn(String title, boolean ask) {
        TableColumn<PriceLadderRow, PriceLadderRow> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        column.setSortable(false);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Region track = new Region();
            private final Region fill = new Region();
            private final StackPane bar = new StackPane(track, fill);
            private final Label amount = new Label();
            private final HBox box = new HBox(8);

            {
                track.getStyleClass().add("ladder-bar-track");
                fill.getStyleClass().add(ask ? "ladder-bar-ask" : "ladder-bar-bid");
                StackPane.setAlignment(fill, ask ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                bar.setMinHeight(16);
                bar.setPrefHeight(16);
                HBox.setHgrow(bar, Priority.ALWAYS);
                amount.setMinWidth(64);
                amount.setAlignment(ask ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                box.setAlignment(Pos.CENTER);
                box.getChildren().setAll(ask ? List.of(bar, amount) : List.of(amount, bar));
            }

            @Override
            protected void updateItem(PriceLadderRow row, boolean empty) {
                super.updateItem(row, empty);
                long size = row == null ? 0L : (ask ? row.askSize() : row.bidSize());
                if (empty || row == null || size <= 0L) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                double ratio = ask ? row.askBarRatio() : row.bidBarRatio();
                fill.prefWidthProperty().bind(bar.widthProperty().multiply(ratio));
                fill.maxWidthProperty().bind(fill.prefWidthProperty());
                amount.setText(sizeLabel(size, ask ? row.askDelta() : row.bidDelta()));
                setGraphic(box);
                setText(null);
            }
        });
        return column;
    }

    private static final javafx.css.PseudoClass CURRENT =
            javafx.css.PseudoClass.getPseudoClass("current-price");

    public javafx.scene.Node root() {
        return root;
    }

    /** 새 호가창을 반영한다. 화면 스레드에서 부른다. */
    public void update(PriceLadderView view) {
        if (view == null) {
            return;
        }
        rows.setAll(view.rows());
        resizeToRows(view.rows().size());
        applySummary(view);
        applyAnnouncement(view);
    }

    /**
     * 물량이 몰린 곳을 문장으로 남긴다.
     *
     * <p>그래프에서는 색으로만 구분되는데, 색은 화면을 볼 수 없는 사용자에게 전달되지
     * 않는다. 벽 구성이 달라졌을 때만 온다.
     */
    public void showWalls(String text) {
        if (text == null || text.isBlank()) {
            walls.setVisible(false);
            walls.setManaged(false);
            return;
        }
        walls.setText(text);
        walls.setAccessibleText(text);
        walls.setVisible(true);
        walls.setManaged(true);
    }

    /** 호가를 받을 수 없는 상태를 감추지 않는다. */
    public void showUnavailable(String reason) {
        rows.clear();
        resizeToRows(0);
        summary.setText(reason);
        summary.setAccessibleText(reason);
        hideAnnouncement();
        showWalls(null);
    }

    private void applySummary(PriceLadderView view) {
        if (view.rows().isEmpty()) {
            String empty = "받은 호가에 잔량이 없습니다.";
            summary.setText(empty);
            summary.setAccessibleText(empty);
            return;
        }
        String text = view.currentPriceRow()
                .map(row -> (tradedCenter ? "현재가 " : "호가 중간가 ") + price(row.price()) + ". ")
                .orElse("")
                + "표시 범위 " + view.highestPrice().map(OrderBookLadderView::price).orElse("-")
                + " 부터 " + view.lowestPrice().map(OrderBookLadderView::price).orElse("-")
                + " 까지, " + view.rows().size() + "단계."
                + " 막대는 최대 잔량 " + NUMBERS.format(view.maxSize()) + "주 기준."
                + (live ? "" : " 실시간 갱신은 오지 않습니다.");
        summary.setText(text);
        summary.setAccessibleText(text);
    }

    /**
     * 실시간 갱신이 오고 있는지 표시한다.
     *
     * <p>장 시간 외에는 조회한 호가만 있고 갱신이 오지 않는다. 그 사실을 적지 않으면
     * 사용자는 멈춘 화면을 보며 값이 최신인지 알 수 없다.
     */
    public void setLive(boolean value) {
        this.live = value;
    }

    private void applyAnnouncement(PriceLadderView view) {
        view.announcementIfPresent().ifPresentOrElse(text -> {
            announcement.setText(text);
            announcement.setAccessibleText(text);
            announcement.setVisible(true);
            announcement.setManaged(true);
        }, this::hideAnnouncement);
    }

    private void hideAnnouncement() {
        announcement.setVisible(false);
        announcement.setManaged(false);
    }

    /**
     * 표를 행 수에 맞춰 키운다.
     *
     * <p>호가는 한눈에 보아야 판단이 된다. 표 안에서 스크롤하게 두면 위아래 호가를 함께
     * 볼 수 없고, 스크린리더로 읽을 때도 보이지 않는 행을 지나치기 쉽다.
     */
    private void resizeToRows(int count) {
        double height = HEADER_HEIGHT + Math.max(1, count) * ROW_HEIGHT;
        table.setMinHeight(height);
        table.setPrefHeight(height);
        table.setMaxHeight(height);
    }

    /** 잔량이 없으면 빈 칸으로 둔다. 0 을 늘어놓으면 표를 읽어 내려갈 때 소음이 된다. */
    private static String sizeLabel(long value, long delta) {
        if (value <= 0L) {
            return "";
        }
        String text = NUMBERS.format(value);
        if (delta > 0) {
            return text + " (+" + NUMBERS.format(delta) + ")";
        }
        if (delta < 0) {
            return text + " (" + NUMBERS.format(delta) + ")";
        }
        return text;
    }

    private String priceLabel(PriceLadderRow row) {
        if (!row.currentPriceRow()) {
            return price(row.price());
        }
        return price(row.price()) + (tradedCenter ? "  현재가" : "  중간가");
    }

    /**
     * 격자 중심이 체결가인지 호가 중간값인지 알려 준다.
     *
     * <p>둘은 다른 값이다. 스프레드가 벌어지면 눈에 띄게 차이 나고, 체결이 나도 호가가
     * 그대로면 중간값은 움직이지 않는다. 중간값을 "현재가" 라고 읽어 주면 사용자가
     * 체결가로 오해한다.
     */
    public void setTradedCenter(boolean value) {
        this.tradedCenter = value;
    }

    private static String price(BigDecimal value) {
        return NUMBERS.format(value) + "원";
    }
}
