package org.ossproject.desktop.view.screen;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.desktop.viewmodel.StockSearchItem;
import org.ossproject.desktop.viewmodel.StockSearchViewModel;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.ossproject.desktop.view.UiKit.*;

/** 종목검색 화면. 검색 제어와 상태는 {@link StockSearchViewModel}에 위임한다. */
public final class SearchScreenView {
    private final StockSearchViewModel viewModel;
    private final Consumer<Screen> navigate;
    private final Consumer<String> status;

    public SearchScreenView(StockSearchViewModel viewModel, Consumer<Screen> navigate, Consumer<String> status) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.navigate = Objects.requireNonNull(navigate, "navigate");
        this.status = Objects.requireNonNull(status, "status");
    }

    public VBox create() {
        Label title = heading("종목검색");
        TextField query = new TextField();
        query.setText(viewModel.currentQuery());
        query.setPromptText("삼성전자, 005930, AAPL처럼 검색");
        query.setAccessibleText("국내와 미국 종목 검색어");
        ComboBox<String> market = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "전체", "국내", "미국", "ETF", "ELW"));
        market.setValue(viewModel.currentMarket());

        TableView<StockSearchItem> results = createResultTable();
        Label resultState = new Label();
        resultState.setWrapText(true);
        resultState.getStyleClass().add("muted-text");
        Label emptyState = new Label();
        emptyState.setWrapText(true);
        results.setPlaceholder(emptyState);

        Consumer<Boolean> filter = focusResults -> {
            String submittedQuery = query.getText() == null ? "" : query.getText().strip();
            if (focusResults) viewModel.recordRecentQuery(submittedQuery);
            String loading = "종목을 조회하고 있습니다.";
            resultState.setText(loading);
            resultState.setAccessibleText("검색 상태. " + loading);
            viewModel.filter(query.getText(), market.getValue()).whenComplete((result, failure) -> {
                if (failure != null) {
                    Platform.runLater(() -> {
                        String message = "종목 검색 화면을 갱신하지 못했습니다.";
                        resultState.setText(message);
                        emptyState.setText(message);
                        status.accept(message);
                    });
                    return;
                }
                if (!result.applied()) return;
                String message = result.message();
                resultState.setText(message);
                resultState.setAccessibleText("검색 상태. " + message);
                emptyState.setText(result.count() == 0 ? message : "");
                status.accept(message);
                if (focusResults && result.count() > 0) {
                    // 검색어와 정확히 일치하는 종목이 있으면 그걸 선택해 둔다. 없으면 첫 행.
                    viewModel.preferredItem().ifPresentOrElse(
                            preferred -> results.getSelectionModel().select(preferred),
                            () -> results.getSelectionModel().selectFirst());
                    results.scrollTo(results.getSelectionModel().getSelectedIndex());
                    Platform.runLater(results::requestFocus);
                }
            });
        };
        Button search = primaryButton("검색", () -> filter.accept(true));
        query.setOnAction(event -> filter.accept(true));
        market.valueProperty().addListener((obs, old, value) -> filter.accept(false));
        Button clear = new Button("검색 초기화");
        clear.setOnAction(event -> {
            query.clear();
            market.setValue("전체");
            filter.accept(false);
            query.requestFocus();
        });
        HBox searchBar = new HBox(10, query, market, search, clear);
        searchBar.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(query, Priority.ALWAYS);
        resultState.setText("검색 준비됨");
        emptyState.setText("종목을 조회하고 있습니다.");

        Runnable openSelected = () -> {
            StockSearchItem selected = results.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("상세 화면에서 볼 종목을 먼저 선택해주세요.");
                results.requestFocus();
                return;
            }
            viewModel.select(selected);
            navigate.accept(Screen.STOCK_DETAIL);
        };
        results.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openSelected.run(); });
        results.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openSelected.run(); });
        VBox body = new VBox(10, title, searchBar, resultState, results);
        body.getStyleClass().addAll("screen-content", "search-screen");
        body.setPadding(new Insets(12));
        body.setMinSize(0, 0);
        VBox.setVgrow(results, Priority.ALWAYS);
        Platform.runLater(() -> filter.accept(false));
        return body;
    }

    private TableView<StockSearchItem> createResultTable() {
        TableView<StockSearchItem> table = new TableView<>(viewModel.items());
        table.setAccessibleText("종목 검색 결과");
        table.setAccessibleHelp("위아래 방향키로 종목을 선택하고 Enter를 누르면 상세 화면을 엽니다.");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(column("시장", StockSearchItem::market));
        table.getColumns().add(column("코드", StockSearchItem::symbol));
        table.getColumns().add(column("종목명", StockSearchItem::name));
        table.getColumns().add(column("거래소", StockSearchItem::exchange));
        table.getColumns().add(column("현재가", StockSearchItem::price));
        table.getColumns().add(column("등락률", StockSearchItem::changeRate));
        table.setMinHeight(0);
        table.setMaxHeight(Double.MAX_VALUE);
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected != null) table.setAccessibleText(selected.accessibleDescription());
        });
        return table;
    }

    private TableColumn<StockSearchItem, String> column(String title, Function<StockSearchItem, String> mapper) {
        TableColumn<StockSearchItem, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue())));
        return column;
    }
}
