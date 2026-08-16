package org.ossproject.desktop.view.screen;

import javafx.beans.property.SimpleStringProperty;
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

    public ScrollPane create() {
        Label title = heading("종목검색");
        TextField query = new TextField();
        query.setPromptText("삼성전자, 005930, AAPL처럼 검색");
        query.setAccessibleText("국내와 미국 종목 검색어");
        ComboBox<String> market = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "전체", "국내", "미국", "ETF", "ELW"));
        market.setValue("전체");

        TableView<StockSearchItem> results = createResultTable();
        Runnable filter = () -> {
            int count = viewModel.filter(query.getText(), market.getValue());
            // 조회 실패와 "결과 없음"은 다른 상황이므로 다르게 읽어 준다.
            status.accept(viewModel.lastError().isBlank()
                    ? "검색 결과 " + count + "건을 표시했습니다."
                    : viewModel.lastError());
        };
        Button search = primaryButton("검색", filter);
        query.setOnAction(event -> filter.run());
        market.valueProperty().addListener((obs, old, value) -> filter.run());
        HBox searchBar = new HBox(10, query, market, search);
        searchBar.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(query, Priority.ALWAYS);

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
        Button open = primaryButton("선택 종목 열기", openSelected);
        Button favorite = new Button("관심종목 추가");
        favorite.setOnAction(event -> {
            StockSearchItem selected = results.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("관심종목에 추가할 종목을 먼저 선택해주세요.");
                results.requestFocus();
                return;
            }
            status.accept(viewModel.addToWatchlist(selected)
                    ? selected.name() + "을 관심종목에 추가했습니다."
                    : selected.name() + "은 이미 관심종목에 있습니다.");
        });

        ListView<String> recent = new ListView<>(viewModel.recentSearches());
        recent.setAccessibleText("최근 검색"); recent.setPrefHeight(130);
        VBox body = new VBox(18, title, searchBar, results, new HBox(10, open, favorite), card("최근 검색", recent));
        return scrollPage("종목검색", body);
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
        table.setPrefHeight(380);
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
