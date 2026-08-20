package org.ossproject.desktop.orderbook;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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

    private final ObservableList<PriceLadderRow> rows = FXCollections.observableArrayList();
    private final TableView<PriceLadderRow> table;
    private final Label summary = new Label("호가를 기다리고 있습니다.");
    private final Label announcement = new Label();
    private final VBox root;

    public OrderBookLadderView(String stockName) {
        Objects.requireNonNull(stockName, "stockName");
        TableColumn<PriceLadderRow, String> askColumn =
                UiKit.textColumn("매도 잔량", row -> size(row.askSize(), row.askDelta()));
        TableColumn<PriceLadderRow, String> priceColumn =
                UiKit.textColumn("가격", row -> price(row.price()));
        TableColumn<PriceLadderRow, String> bidColumn =
                UiKit.textColumn("매수 잔량", row -> size(row.bidSize(), row.bidDelta()));
        table = UiKit.typedTable(stockName + " 호가창 표", rows, askColumn, priceColumn, bidColumn);
        table.setAccessibleHelp("위아래 방향키로 가격대를 이동합니다. 각 행은 가격과 매도·매수 잔량입니다.");
        table.setPrefHeight(360);
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

        root = new VBox(10, summary, announcement, table);
        root.setPadding(new Insets(12));
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
        applySummary(view);
        applyAnnouncement(view);
    }

    /** 호가를 받을 수 없는 상태를 감추지 않는다. */
    public void showUnavailable(String reason) {
        rows.clear();
        summary.setText(reason);
        summary.setAccessibleText(reason);
        hideAnnouncement();
    }

    private void applySummary(PriceLadderView view) {
        String text = view.currentPriceRow()
                .map(row -> "현재가 " + price(row.price()) + ". ")
                .orElse("")
                + "표시 범위 " + view.highestPrice().map(OrderBookLadderView::price).orElse("-")
                + " 부터 " + view.lowestPrice().map(OrderBookLadderView::price).orElse("-")
                + " 까지, " + view.rows().size() + "단계.";
        summary.setText(text);
        summary.setAccessibleText(text);
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

    /** 잔량이 없으면 빈 칸으로 둔다. 0 을 늘어놓으면 표를 읽어 내려갈 때 소음이 된다. */
    private static String size(long value, long delta) {
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

    private static String price(BigDecimal value) {
        return NUMBERS.format(value) + "원";
    }
}
