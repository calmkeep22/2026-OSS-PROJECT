package org.ossproject.desktop.view.screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.viewmodel.DesktopSession;
import org.ossproject.desktop.viewmodel.WatchlistQuoteRow;
import org.ossproject.desktop.viewmodel.WatchlistViewModel;

import java.util.Objects;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/** 관심종목 화면. 저장 모델은 식별 정보만, 표 시세는 조회 결과만 사용한다. */
public final class WatchlistScreenView {
    private final WatchlistViewModel viewModel;
    private final Consumer<WatchlistItem> openStock;
    private final Runnable findStock;
    private final Consumer<String> status;

    public WatchlistScreenView(WatchlistViewModel viewModel, Consumer<WatchlistItem> openStock,
                               Runnable findStock, Consumer<String> status) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.openStock = Objects.requireNonNull(openStock, "openStock");
        this.findStock = Objects.requireNonNull(findStock, "findStock");
        this.status = Objects.requireNonNull(status, "status");
    }

    public ScrollPane create() {
        Label title = heading("관심종목");
        ComboBox<String> group = new ComboBox<>(viewModel.groups());
        group.setValue(DesktopSession.ALL_GROUP);
        Button refresh = new Button("최신 시세 조회");
        refresh.setOnAction(event -> refreshQuotes(refresh));
        Button add = new Button("종목 검색해서 추가");
        Button manageGroup = new Button("그룹 관리");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, group, refresh, manageGroup, add);
        header.setAlignment(Pos.CENTER_LEFT);

        FilteredList<WatchlistQuoteRow> filtered = viewModel.filteredRows();
        group.valueProperty().addListener((obs, old, selected) -> viewModel.applyGroupFilter(filtered, selected));
        TableView<WatchlistQuoteRow> table = watchlistTable("관심종목 목록", filtered);
        table.setPrefHeight(430);
        table.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelected(table); });

        add.setOnAction(event -> findStock.run());
        manageGroup.setOnAction(event -> showGroupManager());
        Button open = primaryButton("선택 종목 열기", () -> openSelected(table));
        Button edit = new Button("선택 설정 수정"); edit.setOnAction(event -> editSelected(table));
        Button remove = new Button("선택 삭제"); remove.setOnAction(event -> removeSelected(table));
        Button moveUp = new Button("위로"); moveUp.setOnAction(event -> moveSelected(table, -1));
        Button moveDown = new Button("아래로"); moveDown.setOnAction(event -> moveSelected(table, 1));
        Button alert = new Button("가격 알림"); alert.setOnAction(event -> editAlert(table));
        FlowPane actions = wrappingRow(8, open, edit, remove, moveUp, moveDown, alert);
        Label help = new Label("Enter: 종목 열기 · Delete: 관심종목 제거 · Ctrl+위/아래: 순서 변경");
        help.getStyleClass().add("muted-text");
        table.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE) removeSelected(table);
            else if (event.getCode() == KeyCode.ENTER) openSelected(table);
            else if (event.isControlDown() && event.getCode() == KeyCode.UP) moveSelected(table, -1);
            else if (event.isControlDown() && event.getCode() == KeyCode.DOWN) moveSelected(table, 1);
        });
        return scrollPage("관심종목", new VBox(18, header, table, actions, help));
    }

    public VBox createUsPanel(Consumer<WatchlistQuoteRow> orderAction) {
        Objects.requireNonNull(orderAction, "orderAction");
        FilteredList<WatchlistQuoteRow> usItems = new FilteredList<>(viewModel.quoteRows(), WatchlistQuoteRow::overseas);
        TableView<WatchlistQuoteRow> table = watchlistTable("미국주식 관심종목", usItems);
        table.setPrefHeight(390);
        Button refresh = new Button("최신 시세 조회");
        refresh.setOnAction(event -> refreshQuotes(refresh));
        Button add = new Button("종목 검색해서 추가"); add.setOnAction(event -> findStock.run());
        Button edit = new Button("선택 설정 수정"); edit.setOnAction(event -> editSelected(table));
        Button remove = new Button("선택 삭제"); remove.setOnAction(event -> removeSelected(table));
        Button alert = new Button("가격 알림"); alert.setOnAction(event -> editAlert(table));
        Runnable orderSelected = () -> {
            WatchlistQuoteRow selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("주문할 미국주식 관심종목을 선택해주세요.");
                table.requestFocus();
            } else if (!selected.quoteAvailable()) {
                status.accept(selected.securityName() + " 시세를 조회한 뒤 주문해주세요.");
            } else orderAction.accept(selected);
        };
        Button order = primaryButton("선택 종목 주문", orderSelected);
        Button open = new Button("선택 종목 상세"); open.setOnAction(event -> openSelected(table));
        table.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelected(table); });
        table.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelected(table); });
        VBox panel = new VBox(12, table, wrappingRow(8, refresh, add, open, edit, remove, alert, order));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private void refreshQuotes(Button button) {
        button.setDisable(true);
        status.accept("관심종목 최신 시세를 조회하고 있습니다.");
        viewModel.refresh().whenComplete((result, failure) -> {
            button.setDisable(false);
            if (failure != null) status.accept("관심종목 시세를 조회하지 못했습니다.");
            else if (result.applied()) status.accept(result.message());
        });
    }

    private TableView<WatchlistQuoteRow> watchlistTable(String accessibleName,
                                                         ObservableList<WatchlistQuoteRow> rows) {
        TableView<WatchlistQuoteRow> table = typedTable(accessibleName, rows,
                textColumn("그룹", WatchlistQuoteRow::group),
                textColumn("종목코드", WatchlistQuoteRow::symbol),
                textColumn("종목", WatchlistQuoteRow::securityName),
                textColumn("현재가", WatchlistQuoteRow::displayPrice),
                textColumn("등락률", WatchlistQuoteRow::displayChange),
                textColumn("거래량", WatchlistQuoteRow::displayVolume),
                textColumn("시세 상태", WatchlistQuoteRow::quoteStatus),
                textColumn("가격 알림", WatchlistQuoteRow::alertText));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) table.setAccessibleText(selected.accessibleDescription());
        });
        return table;
    }

    private void editSelected(TableView<WatchlistQuoteRow> table) {
        WatchlistQuoteRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.accept("수정할 관심종목을 선택해주세요.");
            table.requestFocus();
            return;
        }
        showEditor(selected.item());
    }

    private void showEditor(WatchlistItem existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("관심종목 설정 수정");
        ComboBox<String> group = new ComboBox<>(FXCollections.observableArrayList(
                viewModel.groups().stream().filter(value -> !value.equals(DesktopSession.ALL_GROUP)).toList()));
        group.setValue(existing.group());
        TextField name = readOnlyField(existing.securityName());
        TextField symbol = readOnlyField(existing.symbol());
        TextField exchange = readOnlyField(existing.exchange());
        TextField alert = new TextField(existing.alertText());
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(10);
        addField(form, 0, "그룹", group); addField(form, 1, "종목", name);
        addField(form, 2, "종목코드", symbol); addField(form, 3, "거래소", exchange);
        addField(form, 4, "가격 알림", alert);
        Label note = new Label("현재가·등락률·거래량은 저장하거나 직접 수정하지 않고 조회 결과만 표시합니다.");
        note.setWrapText(true); note.getStyleClass().add("muted-text");
        form.add(note, 0, 5, 2, 1);
        dialog.getDialogPane().setContent(form);
        ButtonType save = new ButtonType("저장", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            try {
                WatchlistItem replacement = new WatchlistItem(group.getValue(), existing.market(), existing.symbol(),
                        existing.securityName(), existing.exchange(), existing.currency(), alert.getText());
                viewModel.save(existing, replacement);
                status.accept(replacement.securityName() + " 관심종목 설정을 저장했습니다.");
            } catch (IllegalArgumentException error) {
                information("입력값을 확인하세요", error.getMessage());
            }
        });
    }

    private TextField readOnlyField(String value) {
        TextField field = new TextField(value); field.setEditable(false); return field;
    }

    private void showGroupManager() {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("관심종목 그룹 관리");
        ObservableList<String> editable = FXCollections.observableArrayList(
                viewModel.groups().stream().filter(value -> !value.equals(DesktopSession.ALL_GROUP)).toList());
        ListView<String> groups = new ListView<>(editable);
        groups.setPrefHeight(240); groups.setAccessibleText("관심종목 그룹 목록");
        TextField value = new TextField(); value.setPromptText("새 그룹명 또는 변경할 이름");
        Button add = new Button("추가"); add.setOnAction(event -> {
            String replacement = value.getText().trim();
            if (viewModel.addGroup(replacement)) {
                editable.add(replacement); value.clear(); status.accept(replacement + " 그룹을 추가했습니다.");
            } else status.accept("비어 있지 않은 새 그룹명을 입력해주세요.");
        });
        Button rename = new Button("이름 변경"); rename.setOnAction(event -> {
            String selected = groups.getSelectionModel().getSelectedItem(); String replacement = value.getText().trim();
            if (viewModel.renameGroup(selected, replacement)) {
                editable.set(editable.indexOf(selected), replacement); value.clear();
                status.accept(selected + " 그룹명을 " + replacement + "(으)로 변경했습니다.");
            } else status.accept("변경할 그룹을 선택하고 중복되지 않은 이름을 입력해주세요.");
        });
        Button delete = new Button("삭제"); delete.setOnAction(event -> {
            String selected = groups.getSelectionModel().getSelectedItem();
            WatchlistViewModel.GroupDeleteResult result = viewModel.deleteGroup(selected);
            if (result == WatchlistViewModel.GroupDeleteResult.DELETED) {
                editable.remove(selected); status.accept(selected + " 그룹을 삭제했습니다.");
            } else if (result == WatchlistViewModel.GroupDeleteResult.IN_USE) {
                information("그룹을 비울 수 없습니다", "그룹 안의 종목을 다른 그룹으로 옮긴 후 삭제해주세요.");
            } else status.accept("삭제할 그룹을 선택해주세요.");
        });
        VBox content = new VBox(10, groups, value, wrappingRow(8, add, rename, delete));
        content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void removeSelected(TableView<WatchlistQuoteRow> table) {
        WatchlistQuoteRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("삭제할 관심종목을 선택해주세요."); table.requestFocus(); return; }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                selected.securityName() + "을 관심종목에서 삭제하시겠습니까?", ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("관심종목 삭제");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            viewModel.remove(selected.item());
            status.accept(selected.securityName() + "을 관심종목에서 삭제했습니다.");
        });
    }

    private void openSelected(TableView<WatchlistQuoteRow> table) {
        WatchlistQuoteRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("열어볼 관심종목을 선택해주세요."); table.requestFocus(); return; }
        if (!selected.quoteAvailable()) {
            status.accept(selected.securityName() + " 시세를 조회하지 못했습니다. 연결 상태를 확인해주세요.");
            return;
        }
        openStock.accept(selected.item());
    }

    private void moveSelected(TableView<WatchlistQuoteRow> table, int direction) {
        WatchlistQuoteRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("순서를 바꿀 관심종목을 선택해주세요."); table.requestFocus(); return; }
        WatchlistItem item = selected.item();
        if (viewModel.move(item, direction)) {
            selectItem(table, item);
            status.accept(item.securityName() + " 순서를 변경했습니다.");
        } else status.accept(direction < 0 ? "이미 첫 번째 관심종목입니다." : "이미 마지막 관심종목입니다.");
    }

    private void editAlert(TableView<WatchlistQuoteRow> table) {
        WatchlistQuoteRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("가격 알림을 설정할 관심종목을 선택해주세요."); table.requestFocus(); return; }
        WatchlistItem item = selected.item();
        TextInputDialog dialog = new TextInputDialog(item.alertText().equals("없음") ? "" : item.alertText());
        dialog.setTitle("가격 알림 설정"); dialog.setHeaderText(item.securityName() + " 목표 가격");
        dialog.setContentText("가격");
        dialog.showAndWait().ifPresent(value -> {
            WatchlistItem replacement = viewModel.setAlert(item, value);
            selectItem(table, replacement);
            status.accept(item.securityName() + " 가격 알림을 저장했습니다.");
        });
    }

    private void selectItem(TableView<WatchlistQuoteRow> table, WatchlistItem item) {
        table.getItems().stream().filter(row -> row.item().equals(item)).findFirst().ifPresent(row -> {
            table.getSelectionModel().select(row); table.scrollTo(row);
        });
    }

    private void information(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(title); alert.showAndWait();
    }
}
