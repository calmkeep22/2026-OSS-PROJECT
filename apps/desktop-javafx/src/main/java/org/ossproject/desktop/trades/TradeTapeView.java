package org.ossproject.desktop.trades;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.view.UiKit;
import org.ossproject.finance.model.Trade;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 체결 목록.
 *
 * <p>표에 초점이 있는 동안에는 갱신을 멈춘다. 읽는 중에 목록이 위로 밀리면 스크린리더로
 * 읽던 자리를 잃는다. 멈춘 동안 몇 건이 밀렸는지는 글자로 알린다.
 */
public final class TradeTapeView {

    private static final NumberFormat NUMBERS = NumberFormat.getIntegerInstance(Locale.KOREA);

    private final ObservableList<Trade> rows = FXCollections.observableArrayList();
    private final TableView<Trade> table;
    private final Label status = new Label("체결을 기다리고 있습니다.");
    private final VBox root;

    public TradeTapeView(String stockName, Consumer<Boolean> onFocusChanged) {
        Objects.requireNonNull(stockName, "stockName");
        Objects.requireNonNull(onFocusChanged, "onFocusChanged");

        TableColumn<Trade, String> timeColumn = UiKit.textColumn("시각", Trade::timeText);
        TableColumn<Trade, String> priceColumn =
                UiKit.textColumn("체결가", trade -> NUMBERS.format(trade.price()) + "원");
        TableColumn<Trade, String> quantityColumn =
                UiKit.textColumn("체결량", trade -> NUMBERS.format(trade.quantity()) + "주");
        // 매수와 매도를 색으로만 구분하면 전달되지 않는다. 칸에 글자로 적는다.
        TableColumn<Trade, String> sideColumn =
                UiKit.textColumn("구분", trade -> trade.side().displayName());

        table = UiKit.typedTable(stockName + " 체결 목록", rows,
                timeColumn, priceColumn, quantityColumn, sideColumn);
        table.setAccessibleHelp("위아래 방향키로 체결을 하나씩 확인합니다. "
                + "표를 보는 동안에는 새 체결이 끼어들지 않습니다.");
        table.setPrefHeight(360);
        table.setPlaceholder(new Label("아직 받은 체결이 없습니다."));
        // 초점이 표에 있으면 갱신을 멈춘다. 읽던 행이 사라지지 않게 하기 위해서다.
        table.focusedProperty().addListener((obs, had, has) -> onFocusChanged.accept(has));

        table.setRowFactory(view -> new TableRow<>() {
            @Override
            protected void updateItem(Trade item, boolean empty) {
                super.updateItem(item, empty);
                setAccessibleText(empty || item == null ? null : item.describe());
            }
        });

        status.setWrapText(true);
        root = new VBox(10, status, table);
        root.setPadding(new Insets(12));
    }

    public javafx.scene.Node root() {
        return root;
    }

    /** 새 목록을 반영한다. 화면 스레드에서 부른다. */
    public void update(List<Trade> trades) {
        rows.setAll(trades);
        if (!trades.isEmpty()) {
            String text = "최근 체결 " + trades.size() + "건. 가장 최근 "
                    + trades.get(0).describe() + ".";
            status.setText(text);
            status.setAccessibleText(text);
        }
    }

    /** 갱신을 멈춘 동안 밀린 건수를 알린다. 0 이면 멈춰 있지 않다는 뜻이다. */
    public void showHeld(int count) {
        if (count <= 0) {
            return;
        }
        String text = "표를 보는 동안 새 체결 " + count + "건이 밀려 있습니다. "
                + "표에서 초점을 옮기면 반영됩니다.";
        status.setText(text);
        status.setAccessibleText(text);
    }

    /** 체결을 받을 수 없는 상태를 감추지 않는다. */
    public void showUnavailable(String reason) {
        rows.clear();
        status.setText(reason);
        status.setAccessibleText(reason);
    }
}
