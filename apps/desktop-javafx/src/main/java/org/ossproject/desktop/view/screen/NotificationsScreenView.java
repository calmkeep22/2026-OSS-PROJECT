package org.ossproject.desktop.view.screen;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 알림 화면.
 *
 * <p>알림은 쌓이기만 하면 아무도 보지 않게 된다. 걸러 보기와 읽음 처리와 지우기를 함께
 * 둔다. 지우기는 되돌릴 수 없으므로 몇 건인지 세어 확인을 받는다.
 *
 * <p>목록은 세션이 들고 있는 것을 그대로 쓴다. 이 화면이 사본을 만들면 다른 화면에서
 * 쌓인 알림이 보이지 않는다.
 */
public final class NotificationsScreenView {

    private final ObservableList<String> entries;
    private final Consumer<String> status;
    /** 목록이 바뀌면 알린다. 앱이 저장 시점을 정한다. */
    private final Runnable onChanged;
    /** 선택한 알림을 읽어 준다. 음성 대기열은 앱이 관리한다. */
    private final BiConsumer<String, String> speak;

    public NotificationsScreenView(ObservableList<String> entries,
                                   Consumer<String> status, Runnable onChanged,
                                   BiConsumer<String, String> speak) {
        this.entries = Objects.requireNonNull(entries, "entries");
        this.status = Objects.requireNonNull(status, "status");
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
        this.speak = Objects.requireNonNull(speak, "speak");
    }

    public ScrollPane create() {
        Label title = heading("알림");
        ComboBox<String> filter = new ComboBox<>(FXCollections.observableArrayList("전체", "주문", "가격", "이상 감지", "연결"));
        filter.setValue("전체");
        Button allRead = new Button("모두 읽음");
        Button clearAll = new Button("전체 지우기");
        clearAll.getStyleClass().add("danger-outline-button");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, filter, allRead, clearAll); header.setAlignment(Pos.CENTER_LEFT);
        FilteredList<String> filtered = new FilteredList<>(entries, value -> true);
        filter.valueProperty().addListener((obs, old, selected) -> filtered.setPredicate(
                value -> selected == null || selected.equals("전체") || value.contains("· " + selected + " ·")));
        ListView<String> notifications = new ListView<>(filtered);
        notifications.setAccessibleText("알림 목록"); notifications.setPrefHeight(430);
        allRead.setOnAction(event -> {
            for (int i = 0; i < entries.size(); i++) entries.set(i, entries.get(i).replace("새 알림 · ", ""));
            status.accept("모든 알림을 읽음 처리했습니다.");
            onChanged.run();
        });
        clearAll.setAccessibleText("저장된 알림 기록 전체 지우기");
        clearAll.setOnAction(event -> {
            if (entries.isEmpty()) {
                status.accept("지울 알림이 없습니다.");
                return;
            }
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "저장된 알림 " + entries.size() + "건을 모두 지우시겠습니까?",
                    ButtonType.OK, ButtonType.CANCEL);
            confirmation.setHeaderText("알림 기록 전체 지우기");
            confirmation.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
                entries.clear();
                onChanged.run();
                status.accept("모든 알림 기록을 지웠습니다.");
            });
        });
        Button listen = new Button("선택 알림 듣기");
        listen.setOnAction(event -> {
            String selected = notifications.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("들을 알림을 먼저 선택해주세요.");
                notifications.requestFocus();
                return;
            }
            speak.accept(selected, "notification-selected");
        });
        Button markRead = new Button("선택 읽음"); markRead.setOnAction(event -> {
            String selected = notifications.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("읽음 처리할 알림을 선택해주세요.");
                notifications.requestFocus();
                return;
            }
            int index = entries.indexOf(selected);
            if (index >= 0) entries.set(index, selected.replace("새 알림 · ", ""));
            status.accept("선택한 알림을 읽음 처리했습니다.");
            onChanged.run();
        });
        Button delete = new Button("선택 삭제"); delete.setOnAction(event -> {
            String selected = notifications.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("삭제할 알림을 선택해주세요.");
                notifications.requestFocus();
                return;
            }
            entries.remove(selected);
            status.accept("선택한 알림을 삭제했습니다.");
            onChanged.run();
        });
        VBox history = new VBox(10, notifications, wrappingRow(8, listen, markRead, delete));
        history.getStyleClass().add("settings-card");
        history.setPadding(new Insets(12));
        return scrollPage("알림", new VBox(12, header, history));
    }

}
