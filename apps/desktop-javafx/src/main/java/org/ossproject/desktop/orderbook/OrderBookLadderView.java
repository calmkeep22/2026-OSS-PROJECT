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
import org.ossproject.finance.model.orderbook.PriceLadderRow;
import org.ossproject.finance.model.orderbook.PriceLadderView;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

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
    /**
     * 큰 글자 모드의 행 높이.
     *
     * <p>행 높이를 고정하지 않으면 표 전체 높이를 미리 셀 수 없어 고정해 두는데, 글자만
     * 커지고 행은 그대로면 글자 아래가 잘린다. 실제로 가격 칸의 "5,210원" 이 밑동만
     * 남은 채로 그려졌다. 글자를 키운 만큼 행도 키운다.
     */
    private static final double LARGE_ROW_HEIGHT = 48;
    private static final double LARGE_HEADER_HEIGHT = 44;
    /**
     * 표 위에 놓인 요약·안내·벽 문구와 그 사이 간격이 차지하는 몫.
     *
     * <p>재서 구하지 않고 넉넉히 잡는다. 실제 높이는 문구가 몇 줄로 접히느냐에 따라 달라져
     * 배치가 끝나야 알 수 있는데, 그때는 이미 잘린 뒤다. 조금 남는 편이 잘리는 것보다 낫다.
     */
    private static final double CHROME_HEIGHT = 82;
    /**
     * 자리가 모자랄 때 그래도 보장하는 단계 수.
     *
     * <p>모든 단계를 담을 높이를 최소 높이로 걸었더니, 큰 글자 모드에서 표가 창보다
     * 길어져 맨 아래 단계가 스크롤도 없이 잘렸다. 글자를 키웠다고 호가가 사라지면
     * 안 된다. 자리가 있으면 전부 펼치고, 없으면 스크롤로 닿게 한다.
     *
     * <p>현재가를 가운데 두고 위아래가 보여야 하므로 홀수로 둔다.
     */
    private static final int GUARANTEED_ROWS = 7;

    private final ObservableList<PriceLadderRow> rows = FXCollections.observableArrayList();
    private final TableView<PriceLadderRow> table;
    private final Label summary = new Label("호가를 기다리고 있습니다.");
    private final Label announcement = new Label();
    private final Label walls = new Label();
    private final VBox root;
    private final javafx.beans.property.ReadOnlyDoubleWrapper requiredHeight =
            new javafx.beans.property.ReadOnlyDoubleWrapper(0);
    private final javafx.beans.property.ReadOnlyDoubleWrapper preferredHeight =
            new javafx.beans.property.ReadOnlyDoubleWrapper(0);
    private double rowHeight = ROW_HEIGHT;
    private double headerHeight = HEADER_HEIGHT;
    private int rowCount;
    private boolean live = true;
    private boolean tradedCenter = true;
    private Consumer<BigDecimal> onPriceSelected = ignored -> { };

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
        table.setAccessibleHelp("위아래 방향키로 가격대를 이동합니다. 주문 화면에서는 행을 누르거나 Enter 키로 지정가를 선택합니다. 각 행은 가격과 매도·매수 잔량입니다.");
        // 행 높이를 고정해 두어야 표 전체 높이를 미리 셀 수 있다.
        table.setFixedCellSize(rowHeight);
        resizeToRows(0);
        // 행마다 읽어 줄 문장을 도메인이 만들어 준다. 매도·매수를 색이 아니라 말로 구분한다.
        table.setRowFactory(view -> new javafx.scene.control.TableRow<>() {
            {
                setOnMouseClicked(event -> {
                    if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                            && event.getClickCount() == 1 && !isEmpty() && getItem() != null) {
                        onPriceSelected.accept(getItem().price());
                    }
                });
            }

            @Override
            protected void updateItem(PriceLadderRow item, boolean empty) {
                super.updateItem(item, empty);
                setAccessibleText(empty || item == null ? null : item.describe());
                pseudoClassStateChanged(CURRENT, item != null && item.currentPriceRow());
            }
        });
        table.setOnKeyPressed(event -> {
            if (event.getCode() != javafx.scene.input.KeyCode.ENTER
                    && event.getCode() != javafx.scene.input.KeyCode.SPACE) {
                return;
            }
            PriceLadderRow selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                onPriceSelected.accept(selected.price());
                event.consume();
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

        root = new VBox(7, summary, announcement, walls, table);
        root.setPadding(new Insets(6, 10, 8, 10));
        root.setMinSize(0, 0);
        root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(table, Priority.ALWAYS);
        followTextSize();
    }

    /**
     * 큰 글자 모드를 따라 행 높이를 바꾼다.
     *
     * <p>글자 크기는 화면 전체 뿌리에 style class 로 걸린다. 설정에서 켜고 끌 때마다
     * 그 목록이 바뀌므로 목록을 지켜본다. 창에 붙기 전에는 뿌리를 알 수 없어 scene 이
     * 생기는 순간에 붙는다.
     */
    private void followTextSize() {
        root.sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene == null || scene.getRoot() == null) {
                return;
            }
            ObservableList<String> classes = scene.getRoot().getStyleClass();
            classes.addListener((javafx.collections.ListChangeListener<String>) change ->
                    applyRowHeight(classes.contains("large-text")));
            applyRowHeight(classes.contains("large-text"));
        });
    }

    private void applyRowHeight(boolean largeText) {
        double wantedRow = largeText ? LARGE_ROW_HEIGHT : ROW_HEIGHT;
        if (wantedRow == rowHeight) {
            return;
        }
        rowHeight = wantedRow;
        headerHeight = largeText ? LARGE_HEADER_HEIGHT : HEADER_HEIGHT;
        table.setFixedCellSize(rowHeight);
        resizeToRows(rowCount);
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

    /** 주문 화면이 호가 가격 선택을 받을 통로. 상세 화면에서는 연결하지 않아도 된다. */
    public void setOnPriceSelected(Consumer<BigDecimal> listener) {
        onPriceSelected = listener == null ? ignored -> { } : listener;
    }

    /** 새 호가창을 반영한다. 화면 스레드에서 부른다. */
    public void update(PriceLadderView view) {
        if (view == null) {
            return;
        }
        rows.setAll(view.rows());
        if (!rows.isEmpty() && table.getSelectionModel().getSelectedItem() == null) {
            int start = view.currentPriceRow().map(rows::indexOf).filter(index -> index >= 0).orElse(0);
            table.getSelectionModel().select(start);
            table.scrollTo(start);
        }
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
        summary.setVisible(true);
        summary.setManaged(true);
        hideAnnouncement();
        showWalls(null);
    }

    private void applySummary(PriceLadderView view) {
        if (view.rows().isEmpty()) {
            String empty = "받은 호가에 잔량이 없습니다.";
            summary.setText(empty);
            summary.setAccessibleText(empty);
            summary.setVisible(true);
            summary.setManaged(true);
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
        // 정상 호가에서는 표와 중간가 행이 같은 정보를 이미 보여 준다. 긴 요약 문장을
        // 화면 위에 반복하지 않고, 키보드·스크린리더 사용자는 표의 도움말로 확인한다.
        summary.setVisible(false);
        summary.setManaged(false);
        table.setAccessibleHelp("위아래 방향키로 가격대를 이동합니다. " + text);
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
        rowCount = count;
        int rows = Math.max(1, count);
        double full = headerHeight + rows * rowHeight;
        // 자리가 있으면 전부 펼친다. pref 와 max 를 모든 단계에 맞춰 두면 표 안에서
        // 스크롤할 일이 없고, 지금까지와 똑같이 한눈에 들어온다.
        table.setPrefHeight(full);
        table.setMaxHeight(full);
        // 자리가 없으면 여기까지 줄어들고 나머지는 표 안에서 스크롤된다. 방향키로
        // 옮기면 표가 따라 스크롤하므로 스크린리더도 모든 단계에 닿는다. 잘라 내는
        // 것과 다르다 — 사라지는 단계가 없다.
        double floor = headerHeight + Math.min(rows, GUARANTEED_ROWS) * rowHeight;
        table.setMinHeight(floor);

        // 표만 키워서는 부족하다. 바깥이 SplitPane 이면 자식의 pref 를 무시하고 제 기본
        // 높이(400)를 쓰기 때문에 아래 단계가 잘린다. 담는 쪽이 최소 높이로 걸 값과
        // 넉넉할 때 쓸 값을 나눠 알려 준다.
        // root 를 보지 않는다. 생성자가 root 를 만들기 전에 이 메서드를 부른다.
        requiredHeight.set(floor + CHROME_HEIGHT);
        preferredHeight.set(full + CHROME_HEIGHT);
    }

    /**
     * 이 칸이 최소한 얼마는 있어야 쓸 만한지.
     *
     * <p>담는 쪽이 이 값을 최소 높이로 건다. 여기까지는 창이 아무리 짧아도 내주고,
     * 모자란 만큼은 표 안에서 스크롤된다.
     */
    public javafx.beans.property.ReadOnlyDoubleProperty requiredHeight() {
        return requiredHeight.getReadOnlyProperty();
    }

    /**
     * 모든 단계를 한눈에 보려면 몇 픽셀이 필요한지.
     *
     * <p>호가는 한눈에 보아야 판단이 된다. 담는 쪽이 이 값을 기준 높이로 걸어 두면,
     * 자리가 있는 한 스크롤 없이 위아래 단계가 함께 보인다.
     */
    public javafx.beans.property.ReadOnlyDoubleProperty preferredHeight() {
        return preferredHeight.getReadOnlyProperty();
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
