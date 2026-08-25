package org.ossproject.desktop.view;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
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
        useBrowserLikeScrolling(scroll);
        return scroll;
    }

    /**
     * 마우스 휠을 브라우저에 가까운 줄 이동량으로 맞춘다.
     *
     * <p>JavaFX 기본 ScrollPane은 Windows의 휠 한 칸이 지나치게 짧다. 줄 단위 휠은
     * 약 90px씩 옮기고, 픽셀 단위 장치는 원래 이동량을 적당히 가속한다.
     */
    public static void useBrowserLikeScrolling(ScrollPane scroll) {
        // JavaFX 기본 pannable은 ScrollPane이 중첩된 주문 화면에서 한 방향의 드래그를
        // 바깥 ScrollPane이 먼저 가져가는 경우가 있다. 직접 시작 위치를 기억해 양방향을
        // 같은 계산으로 처리한다.
        scroll.setPannable(false);
        double[] dragStartY = new double[1];
        double[] dragStartValue = new double[1];
        boolean[] draggingPage = new boolean[1];
        scroll.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!event.isPrimaryButtonDown() || isInteractiveDragTarget(event.getTarget(), scroll)) {
                draggingPage[0] = false;
                return;
            }
            double travel = scroll.getContent().getLayoutBounds().getHeight()
                    - scroll.getViewportBounds().getHeight();
            if (travel <= 0) {
                draggingPage[0] = false;
                return;
            }
            dragStartY[0] = event.getSceneY();
            dragStartValue[0] = scroll.getVvalue();
            draggingPage[0] = true;
        });
        scroll.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!draggingPage[0] || !event.isPrimaryButtonDown()) return;
            double travel = scroll.getContent().getLayoutBounds().getHeight()
                    - scroll.getViewportBounds().getHeight();
            if (travel <= 0) return;
            double range = scroll.getVmax() - scroll.getVmin();
            // 터치식으로 콘텐츠를 붙잡는 반대 방향이 아니라 스크롤바와 같은 방향이다.
            // 위로 끌면 위로, 아래로 끌면 아래로 이동해야 마우스 사용자가 예측할 수 있다.
            double draggedPixels = event.getSceneY() - dragStartY[0];
            double next = dragStartValue[0] + draggedPixels / travel * range;
            scroll.setVvalue(Math.max(scroll.getVmin(), Math.min(scroll.getVmax(), next)));
            event.consume();
        });
        scroll.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> draggingPage[0] = false);
        // 가장 안쪽 ScrollPane은 필터 단계에서 즉시 움직인다. 바깥 ScrollPane은 안쪽을
        // 먼저 지나가게 하고, 안쪽이 끝에 닿아 이벤트가 남았을 때 버블 단계에서 이어받는다.
        scroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!hasNestedScrollableBetween(event.getTarget(), scroll)) {
                applyWheelScroll(scroll, event);
            }
        });
        scroll.addEventHandler(ScrollEvent.SCROLL, event -> {
            if (!event.isConsumed()) applyWheelScroll(scroll, event);
        });
    }

    private static void applyWheelScroll(ScrollPane scroll, ScrollEvent event) {
        if (event.isDirect() || event.isInertia() || event.isControlDown() || event.isMetaDown()) return;
        double travel = scroll.getContent().getLayoutBounds().getHeight()
                - scroll.getViewportBounds().getHeight();
        if (travel <= 0 || (event.getTextDeltaY() == 0 && event.getDeltaY() == 0)) return;
        double range = scroll.getVmax() - scroll.getVmin();
        double pixels = event.getTextDeltaYUnits() == ScrollEvent.VerticalTextScrollUnits.LINES
                ? event.getTextDeltaY() * 90.0
                : event.getDeltaY() * 1.8;
        double next = scroll.getVvalue() - pixels / travel * range;
        double clamped = Math.max(scroll.getVmin(), Math.min(scroll.getVmax(), next));
        if (Math.abs(clamped - scroll.getVvalue()) > 1e-8) {
            scroll.setVvalue(clamped);
            event.consume();
        }
    }

    private static boolean hasNestedScrollableBetween(Object target, ScrollPane owner) {
        if (!(target instanceof Node node)) return false;
        for (Node current = node; current != null && current != owner; current = current.getParent()) {
            if (current instanceof ScrollPane
                    || current instanceof TableView<?>
                    || current instanceof TreeTableView<?>
                    || current instanceof ListView<?>
                    || current instanceof TreeView<?>
                    || current instanceof TextArea) return true;
        }
        return false;
    }

    /** Keeps page dragging away from controls whose own click/drag behavior must win. */
    private static boolean isInteractiveDragTarget(Object target, ScrollPane owner) {
        if (!(target instanceof Node node)) return true;
        for (Node current = node; current != null && current != owner; current = current.getParent()) {
            if (current instanceof Control && !(current instanceof Label)) return true;
            if (current.getStyleClass().contains("split-pane-divider")) return true;
        }
        return false;
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

    /** 모든 별도 창을 앱의 금융 UI 테마와 같은 카드형 대화상자로 맞춘다. */
    public static void styleDialog(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        String stylesheet = UiKit.class.getResource("/styles/application.css").toExternalForm();
        if (!pane.getStylesheets().contains(stylesheet)) pane.getStylesheets().add(stylesheet);
        pane.getStyleClass().addAll("app-dialog", "figma-neutral-theme");
        pane.setMinWidth(440);
        pane.setPrefWidth(520);

        if (dialog instanceof Alert alert) {
            alert.setGraphic(null);
            pane.getStyleClass().add("dialog-" + alert.getAlertType().name().toLowerCase());
        } else if (!(dialog instanceof TextInputDialog) && pane.getHeader() == null
                && dialog.getTitle() != null && !dialog.getTitle().isBlank()) {
            Label title = new Label(dialog.getTitle());
            title.getStyleClass().add("app-dialog-title");
            pane.setHeader(title);
        }

        dialog.setOnShowing(event -> {
            String title = dialog.getTitle() == null ? "" : dialog.getTitle();
            String header = dialog instanceof Alert alert && alert.getHeaderText() != null
                    ? alert.getHeaderText() : "";
            boolean destructive = (title + ' ' + header).matches(".*(삭제|취소|매도).*" );
            for (ButtonType type : pane.getButtonTypes()) {
                Node node = pane.lookupButton(type);
                if (node == null) continue;
                if (type.getButtonData().isDefaultButton()) {
                    node.getStyleClass().add(destructive ? "danger-button" : "primary-button");
                } else if (type.getButtonData().isCancelButton()) {
                    node.getStyleClass().add("secondary-button");
                }
            }
        });
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
        // 큰 글자에서 9개 열을 화면 폭에 강제로 압축하면 값이 잘린다. 각 열에 읽을 수 있는
        // 최소 폭을 주고 표 자체의 가로 스크롤로 이동하게 한다.
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        double[] widths = {150, 125, 150, 90, 125, 85, 85, 85, 125};
        for (int index = 0; index < table.getColumns().size(); index++) {
            TableColumn<ObservableList<String>, ?> column = table.getColumns().get(index);
            column.setMinWidth(widths[index]);
            column.setPrefWidth(widths[index]);
        }
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
