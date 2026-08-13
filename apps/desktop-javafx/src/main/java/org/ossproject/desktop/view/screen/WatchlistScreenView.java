package org.ossproject.desktop.view.screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import org.ossproject.desktop.viewmodel.WatchlistViewModel;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/** 관심종목 화면. 대화상자는 View, 변경 규칙은 ViewModel이 담당한다. */
public final class WatchlistScreenView {
    private final WatchlistViewModel viewModel;
    private final Consumer<String> openStock;
    private final Consumer<String> status;

    public WatchlistScreenView(WatchlistViewModel viewModel, Consumer<String> openStock, Consumer<String> status) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.openStock = Objects.requireNonNull(openStock, "openStock");
        this.status = Objects.requireNonNull(status, "status");
    }

    public ScrollPane create() {
        Label title = heading("관심종목");
        ComboBox<String> group = new ComboBox<>(viewModel.groups()); group.setValue("전체");
        Button add = new Button("종목 추가"); Button manageGroup = new Button("그룹 관리");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, group, manageGroup, add); header.setAlignment(Pos.CENTER_LEFT);

        FilteredList<ObservableList<String>> filtered = viewModel.filteredRows();
        group.valueProperty().addListener((obs, old, selected) -> viewModel.applyGroupFilter(filtered, selected));
        TableView<ObservableList<String>> table = textTable("관심종목 목록", filtered,
                "그룹", "종목", "현재가", "등락률", "거래량", "가격 알림");
        table.setPrefHeight(430);
        table.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelected(table); });

        add.setOnAction(event -> showEditor(null));
        manageGroup.setOnAction(event -> showGroupManager());
        Button edit = new Button("선택 수정"); edit.setOnAction(event -> showEditor(table.getSelectionModel().getSelectedItem()));
        Button remove = new Button("선택 삭제"); remove.setOnAction(event -> removeSelected(table));
        Button moveUp = new Button("위로"); moveUp.setOnAction(event -> moveSelected(table, -1));
        Button moveDown = new Button("아래로"); moveDown.setOnAction(event -> moveSelected(table, 1));
        Button alert = new Button("가격 알림"); alert.setOnAction(event -> editAlert(table));
        FlowPane actions = wrappingRow(8, edit, remove, moveUp, moveDown, alert);
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

    public VBox createUsPanel(Runnable orderAction) {
        FilteredList<ObservableList<String>> usRows = new FilteredList<>(viewModel.rows(),
                row -> row.size() > 2 && row.get(2).startsWith("$"));
        TableView<ObservableList<String>> table = textTable("미국주식 관심종목", usRows,
                "그룹", "종목", "현재가", "등락률", "거래량", "가격 알림");
        table.setPrefHeight(390);
        Button add = new Button("종목 추가"); add.setOnAction(event -> showEditor(null));
        Button edit = new Button("선택 수정"); edit.setOnAction(event -> showEditor(table.getSelectionModel().getSelectedItem()));
        Button remove = new Button("선택 삭제"); remove.setOnAction(event -> removeSelected(table));
        Button alert = new Button("가격 알림"); alert.setOnAction(event -> editAlert(table));
        Button order = primaryButton("선택 종목 주문", orderAction);
        table.setOnMouseClicked(event -> { if (event.getClickCount() == 2) orderAction.run(); });
        VBox panel = new VBox(12, table, wrappingRow(8, add, edit, remove, alert, order));
        panel.setPadding(new Insets(12)); return panel;
    }

    private void showEditor(ObservableList<String> existing) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "관심종목 추가" : "관심종목 수정");
        ComboBox<String> group = new ComboBox<>(FXCollections.observableArrayList(
                viewModel.groups().stream().filter(value -> !value.equals("전체")).toList()));
        if (!group.getItems().isEmpty()) group.setValue(existing == null ? group.getItems().get(0) : existing.get(0));
        TextField name = new TextField(existing == null ? "" : existing.get(1));
        TextField price = new TextField(existing == null ? "0원" : existing.get(2));
        TextField change = new TextField(existing == null ? "0.00%" : existing.get(3));
        TextField volume = new TextField(existing == null ? "0" : existing.get(4));
        TextField alert = new TextField(existing == null ? "없음" : existing.get(5));
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(10);
        addField(form, 0, "그룹", group); addField(form, 1, "종목명 또는 티커", name);
        addField(form, 2, "현재가", price); addField(form, 3, "등락률", change);
        addField(form, 4, "거래량", volume); addField(form, 5, "가격 알림", alert);
        dialog.getDialogPane().setContent(form);
        ButtonType save = new ButtonType("저장", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            try {
                viewModel.save(existing, List.of(group.getValue(), name.getText().trim(), price.getText().trim(),
                        change.getText().trim(), volume.getText().trim(), alert.getText().isBlank() ? "없음" : alert.getText().trim()));
                status.accept(name.getText().trim() + " 관심종목을 저장했습니다.");
            } catch (IllegalArgumentException error) {
                information("종목명을 확인하세요", error.getMessage());
            }
        });
    }

    private void showGroupManager() {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("관심종목 그룹 관리");
        ObservableList<String> editable = FXCollections.observableArrayList(
                viewModel.groups().stream().filter(value -> !value.equals("전체")).toList());
        ListView<String> groups = new ListView<>(editable); groups.setPrefHeight(240); groups.setAccessibleText("관심종목 그룹 목록");
        TextField value = new TextField(); value.setPromptText("새 그룹명 또는 변경할 이름");
        Button add = new Button("추가"); add.setOnAction(event -> {
            if (viewModel.addGroup(value.getText())) { editable.add(value.getText().trim()); value.clear(); }
        });
        Button rename = new Button("이름 변경"); rename.setOnAction(event -> {
            String selected = groups.getSelectionModel().getSelectedItem(); String replacement = value.getText().trim();
            if (viewModel.renameGroup(selected, replacement)) {
                editable.set(editable.indexOf(selected), replacement); value.clear();
            }
        });
        Button delete = new Button("삭제"); delete.setOnAction(event -> {
            String selected = groups.getSelectionModel().getSelectedItem();
            WatchlistViewModel.GroupDeleteResult result = viewModel.deleteGroup(selected);
            if (result == WatchlistViewModel.GroupDeleteResult.DELETED) editable.remove(selected);
            else if (result == WatchlistViewModel.GroupDeleteResult.IN_USE)
                information("그룹을 비울 수 없습니다", "그룹 안의 종목을 다른 그룹으로 옮긴 후 삭제해주세요.");
        });
        VBox content = new VBox(10, groups, value, wrappingRow(8, add, rename, delete)); content.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE); dialog.showAndWait();
    }

    private void removeSelected(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("삭제할 관심종목을 선택해주세요."); return; }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, selected.get(1) + "을 관심종목에서 삭제하시겠습니까?", ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("관심종목 삭제");
        confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> viewModel.remove(selected));
    }

    private void openSelected(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("열어볼 관심종목을 선택해주세요."); return; }
        openStock.accept(selected.get(1));
    }

    private void moveSelected(TableView<ObservableList<String>> table, int direction) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (viewModel.move(selected, direction)) table.getSelectionModel().select(selected);
    }

    private void editAlert(TableView<ObservableList<String>> table) {
        ObservableList<String> selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) { status.accept("가격 알림을 설정할 관심종목을 선택해주세요."); return; }
        TextInputDialog dialog = new TextInputDialog(selected.get(5).equals("없음") ? "" : selected.get(5));
        dialog.setTitle("가격 알림 설정"); dialog.setHeaderText(selected.get(1) + " 목표 가격"); dialog.setContentText("가격");
        dialog.showAndWait().ifPresent(value -> { viewModel.setAlert(selected, value); table.refresh(); });
    }

    private void information(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setHeaderText(title); alert.showAndWait();
    }
}
