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
    /**
     * 고른 종목을 읽어 줄 곳.
     *
     * <p>목록을 방향키로 훑을 때 무엇이 선택됐는지 소리로 알려 준다. 행마다
     * accessibleText 는 이미 붙여 두었지만 그것은 바깥 스크린리더의 몫이고, 이 앱의
     * 화면 읽기를 쓰는 사용자에게는 아무 소리도 나지 않았다.
     */
    private final Consumer<String> announce;
    /** 조회 결과를 알릴 곳. 목록 선택 안내와 나눠야 서로 밀어내지 않는다. */
    private final Consumer<String> announceOutcome;

    public SearchScreenView(StockSearchViewModel viewModel, Consumer<Screen> navigate,
                            Consumer<String> status) {
        this(viewModel, navigate, status, text -> { });
    }

    public SearchScreenView(StockSearchViewModel viewModel, Consumer<Screen> navigate,
                            Consumer<String> status, Consumer<String> announce) {
        this(viewModel, navigate, status, announce, announce);
    }

    public SearchScreenView(StockSearchViewModel viewModel, Consumer<Screen> navigate,
                            Consumer<String> status, Consumer<String> announce,
                            Consumer<String> announceOutcome) {
        this.announceOutcome = Objects.requireNonNull(announceOutcome, "announceOutcome");
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.navigate = Objects.requireNonNull(navigate, "navigate");
        this.status = Objects.requireNonNull(status, "status");
        this.announce = Objects.requireNonNull(announce, "announce");
    }

    public VBox create() {
        Label title = heading("종목검색");
        TextField query = new TextField();
        query.setText(viewModel.currentQuery());
        query.setPromptText("삼성전자 또는 005930처럼 검색");
        query.setAccessibleText("국내 종목 검색어");
        ComboBox<String> market = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                "전체", "국내", "ETF", "ELW"));
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
                // 결과를 아는 곳은 여기다. 부르는 쪽에서 미리 적어 두면 이 갱신이
                // 그것을 덮어쓴다 — 실제로 "검색 결과가 없습니다" 가 그렇게 사라졌다.
                //
                // 못 찾았을 때는 무엇으로 찾았는지를 되읽어 준다. 한글 조합이 끼면 친
                // 것과 들어간 것이 다를 수 있는데, 화면을 볼 수 없으면 그것을 확인할
                // 방법이 검색칸을 되짚어 읽는 것뿐이다.
                // 검색어가 없으면 조회한 것이 아니다. 화면이 만들어질 때 한 번 도는
                // 초기 갱신까지 "검색 결과가 없습니다" 라고 말해서, 검색한 적도 없는
                // 사용자가 그 말을 들었다.
                if (!submittedQuery.isBlank()) {
                    announceOutcome.accept(outcomeSentence(submittedQuery, result.count()));
                }
                // 검색칸에서 Enter 를 눌렀는데 찾는 것이 분명하면 바로 연다.
                // 지금까지는 목록에 세워 두고 Enter 를 한 번 더 받았다. 위쪽 통합
                // 검색은 이미 바로 열어 주는데 여기만 달라서, 같은 이름을 같은 방식으로
                // 쳤는데 화면마다 다르게 움직였다.
                if (focusResults) {
                    StockSearchItem direct = result.count() == 1
                            ? viewModel.items().get(0)
                            : viewModel.exactMatch(submittedQuery).orElse(null);
                    if (direct != null) {
                        viewModel.select(direct);
                        navigate.accept(Screen.STOCK_DETAIL);
                        return;
                    }
                }
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
        // 이미 결과가 있으면 목록에 초점을 둔다. 검색칸에 두면 방향키가 듣지 않아
        // 목록까지 탭을 세 번 눌러야 한다. 소리로 쓰는 사용자에게 탭 한 번은 그냥
        // 한 번이 아니라 "여기가 어디인지" 를 매번 다시 확인하는 일이다.
        //
        // 결과가 없으면 검색칸이 맞다. 훑을 것이 없는데 목록에 세워 두면 아무 데도
        // 못 간다. 검색칸은 목록에서 Shift+Tab 한 번, 또는 Alt+S 로 언제든 돌아간다.
        Platform.runLater(() -> {
            if (viewModel.items().isEmpty()) {
                query.requestFocus();
                return;
            }
            if (results.getSelectionModel().getSelectedItem() == null) {
                results.getSelectionModel().selectFirst();
            }
            results.requestFocus();
        });
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
            if (selected == null) {
                return;
            }
            table.setAccessibleText(selected.accessibleDescription());
            // 몇 번째인지 함께 말한다. 눈으로 보면 한눈에 세어지는 것이 소리로는
            // 세어지지 않아, 목록의 어디쯤인지 알 방법이 없다.
            int position = table.getSelectionModel().getSelectedIndex() + 1;
            int total = table.getItems().size();
            announce.accept(selected.name() + ", " + selected.symbol()
                    + ", " + total + "건 중 " + position + "번째");
        });
        return table;
    }

    /**
     * 조회 결과를 소리로 전할 한 문장.
     *
     * <p>검색어가 있을 때만 부른다. 빈 검색어로 부르면 "찾았는데 없다" 가 되는데, 그것은
     * 사실이 아니다 — 찾은 적이 없다.
     */
    private static String outcomeSentence(String query, int count) {
        String asked = "현재 " + query + " 라고 검색하셨습니다. ";
        if (count == 0) {
            return "검색 결과가 없습니다. " + asked + "다른 이름이나 종목코드로 찾아보세요.";
        }
        return asked + "검색 결과 " + count + "건입니다. 위아래 화살표로 고르고 Enter 로 엽니다.";
    }

    private TableColumn<StockSearchItem, String> column(String title, Function<StockSearchItem, String> mapper) {
        TableColumn<StockSearchItem, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue())));
        return column;
    }
}
