package org.ossproject.desktop.view;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** 모든 화면이 공유하는 접근 가능한 JavaFX 컴포넌트 팩토리. */
import org.ossproject.finance.model.order.Order;

import static org.ossproject.desktop.presentation.Formatters.orderTime;

import org.ossproject.desktop.presentation.Formatters;

public final class UiKit {
    private UiKit() {}

    public static ScrollPane scrollPage(String accessibleName, VBox body) {
        body.setPadding(new Insets(20));
        body.setFillWidth(true);
        body.setMinWidth(0);
        body.getStyleClass().add("screen-content");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setAccessibleText(accessibleName);
        scroll.getStyleClass().add("workspace-scroll");
        return scroll;
    }

    public static FlowPane wrappingRow(double gap, Node... nodes) {
        FlowPane pane = new FlowPane(gap, gap, nodes);
        pane.setAlignment(Pos.CENTER_LEFT);
        pane.setPrefWrapLength(900);
        pane.setMinWidth(0);
        pane.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    public static VBox summaryCard(String label, String value, String detail, String tone) {
        Label name = new Label(label); name.getStyleClass().add("metric-label");
        Label amount = new Label(value); amount.getStyleClass().add("metric-value"); amount.setWrapText(true);
        Label copy = new Label(detail); copy.getStyleClass().add("metric-detail"); copy.setWrapText(true);
        VBox card = new VBox(7, name, amount, copy);
        card.getStyleClass().addAll("summary-card", "tone-" + tone);
        card.setAccessibleText(label + ", " + value + ", " + detail);
        card.setPrefWidth(210); card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    public static VBox compactMarketCard(String label, String value, String change, boolean positive) {
        Label name = new Label(label); name.getStyleClass().add("metric-label");
        Label amount = new Label(value); amount.getStyleClass().add("compact-value");
        Label delta = new Label(change); delta.getStyleClass().add(positive ? "positive-text" : "negative-text");
        VBox card = new VBox(5, name, amount, delta); card.getStyleClass().add("compact-market-card");
        card.setPrefWidth(190); card.setMaxWidth(Double.MAX_VALUE);
        card.setAccessibleText(label + ", " + value + ", " + change);
        return card;
    }

    public static VBox miniMetric(String label, String value) {
        Label name = new Label(label); name.getStyleClass().add("metric-label");
        Label amount = new Label(value); amount.getStyleClass().add("mini-value");
        VBox card = new VBox(4, name, amount); card.getStyleClass().add("mini-metric");
        card.setPrefWidth(140); card.setMaxWidth(Double.MAX_VALUE);
        card.setAccessibleText(label + " " + value); return card;
    }

    public static VBox card(String title, Node... content) {
        VBox card = new VBox(12); card.getStyleClass().add("panel-card"); card.setPadding(new Insets(18));
        card.getChildren().add(sectionHeading(title)); card.getChildren().addAll(content); return card;
    }

    public static Button primaryButton(String text, Runnable action) {
        Button button = new Button(text); button.getStyleClass().add("primary-button");
        button.setOnAction(event -> action.run()); return button;
    }

    public static Label styledLabel(String text, String styleClass) {
        Label label = new Label(text); label.getStyleClass().add(styleClass); return label;
    }

    public static HBox informationRow(String name, String value) {
        return informationRow(name, new Label(value));
    }

    public static HBox informationRow(String name, Label amount) {
        Label label = new Label(name); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        amount.getStyleClass().add("information-value");
        HBox row = new HBox(10, label, spacer, amount); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("information-row");
        row.setAccessibleText(name + " " + amount.getText()); return row;
    }

    public static VBox progressMetric(String name, double progress, String value) {
        Label label = new Label(name); Label amount = new Label(value); amount.getStyleClass().add("information-value");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        ProgressBar bar = new ProgressBar(progress); bar.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(7, new HBox(10, label, spacer, amount), bar); box.setAccessibleText(name + " " + value); return box;
    }

    public static VBox labeledControl(String title, Control control) {
        Label label = new Label(title); label.setLabelFor(control); return new VBox(5, label, control);
    }

    public static Tab tab(String title, Node content) {
        Tab tab = new Tab(title, content); tab.setClosable(false); return tab;
    }

    public static void addInfo(GridPane grid, int column, int row, String name, String value) {
        VBox info = miniMetric(name, value); grid.add(info, column, row);
        GridPane.setHgrow(info, Priority.ALWAYS);
    }

    public static String[] row(String... values) {
        return values;
    }

    public static TableView<ObservableList<String>> textTable(String accessibleName, List<String[]> rows, String... headers) {
        ObservableList<ObservableList<String>> items = FXCollections.observableArrayList();
        rows.forEach(values -> items.add(FXCollections.observableArrayList(values)));
        return textTable(accessibleName, items, headers);
    }

    @SafeVarargs
    public static <T> TableView<T> typedTable(String accessibleName, ObservableList<T> items,
                                               TableColumn<T, String>... columns) {
        TableView<T> table = new TableView<>(items);
        table.setAccessibleText(accessibleName);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().setAll(columns);
        return table;
    }

    public static <T> TableColumn<T, String> textColumn(String title, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(value.apply(data.getValue())));
        return column;
    }

    @SuppressWarnings("unchecked")
    public static TableView<ObservableList<String>> textTable(String accessibleName,
                                                               ObservableList<ObservableList<String>> items,
                                                               String... headers) {
        TableView<ObservableList<String>> table = new TableView<>(items);
        table.setAccessibleText(accessibleName); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        for (int i = 0; i < headers.length; i++) {
            final int index = i;
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(headers[i]);
            column.setCellValueFactory(data -> new SimpleStringProperty(
                    data.getValue().size() > index ? data.getValue().get(index) : ""));
            table.getColumns().add(column);
        }
        return table;
    }

    public static Label stateBanner(String text, String tone) {
        Label label = new Label(text); label.setWrapText(true);
        label.getStyleClass().addAll("state-banner", "state-" + tone);
        label.setAccessibleText(text); return label;
    }

    public static CheckBox setting(String text, boolean selected, Consumer<Boolean> action) {
        CheckBox check = new CheckBox(text); check.setSelected(selected); check.getStyleClass().add("setting-toggle");
        check.selectedProperty().addListener((obs, old, value) -> action.accept(value)); return check;
    }

    public static TextField disabledValue(String value) {
        TextField field = new TextField(value); field.setEditable(false); return field;
    }

    /**
     * 아직 증권사와 연동하지 않은 화면에 대신 보여 줄 안내.
     *
     * <p>값을 지어내 채우지 않는다. 화면을 볼 수 없는 사용자는 표에 있는 숫자가 실제 시장
     * 값인지 확인할 방법이 없으므로, 없는 데이터는 없다고 말하는 편이 안전하다.
     *
     * @param what 어떤 데이터인지
     * @param tr   연동에 사용할 키움 TR. 후속 작업을 알아볼 수 있게 함께 적는다
     */
    public static Node notConnectedPanel(String what, String tr) {
        Label heading = new Label(what + " 데이터는 아직 연동되지 않았습니다.");
        heading.getStyleClass().add("safety-note");
        heading.setWrapText(true);
        Label detail = new Label("실제 값을 받아오기 전까지 임의의 숫자를 표시하지 않습니다. "
                + "연동 예정 항목: " + tr);
        detail.setWrapText(true);
        VBox panel = new VBox(10, heading, detail);
        panel.setPadding(new Insets(20));
        panel.setAccessibleText(what + " 데이터는 아직 연동되지 않았습니다. "
                + "실제 값을 받아오기 전까지 임의의 숫자를 표시하지 않습니다.");
        return panel;
    }

    /**
     * 주문 상태 표.
     *
     * <p>미체결과 체결을 같은 열 구성으로 보여 준다. 계좌 화면과 주문 화면이 함께 쓴다.
     */
    public static TableView<ObservableList<String>> orderStatusTable(boolean open, List<Order> allOrders) {
        List<Order> orders = allOrders.stream()
                .filter(order -> open ? !order.status().isTerminal() : order.status().isTerminal())
                .toList();
        // 주문번호를 함께 보여 준다. 취소·정정은 이 번호로 원주문을 지정하고, 사용자도
        // 증권사 화면과 대조할 수 있어야 한다.
        List<String[]> rows = orders.stream().map(order -> row(
                order.orderId(),
                orderTime(order),
                order.name(),
                order.side().displayName(),
                order.limitPrice() == null ? "시장가" : Formatters.won(order.limitPrice()),
                Long.toString(order.quantity()),
                Long.toString(order.filledQuantity()),
                Long.toString(order.remainingQuantity()),
                order.status().displayName())).toList();
        TableView<ObservableList<String>> table = textTable(open ? "미체결 주문" : "체결·종료 주문", rows,
                "주문번호", "시간", "종목", "구분", "주문가", "수량", "체결", "잔여", "상태");
        table.setPlaceholder(new Label(open
                ? "미체결 주문이 없습니다." : "체결되었거나 종료된 주문이 없습니다."));
        return table;
    }


    public static Label heading(String text) {
        Label label = new Label(text); label.getStyleClass().add("title"); return label;
    }

    public static Label sectionHeading(String text) {
        Label label = new Label(text); label.getStyleClass().add("section-title"); return label;
    }

    public static void addField(GridPane form, int row, String labelText, Control control) {
        Label label = new Label(labelText); label.setLabelFor(control); control.setMaxWidth(Double.MAX_VALUE);
        form.add(label, 0, row); form.add(control, 1, row); GridPane.setHgrow(control, Priority.ALWAYS);
    }
}
