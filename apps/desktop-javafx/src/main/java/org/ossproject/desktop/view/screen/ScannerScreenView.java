package org.ossproject.desktop.view.screen;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.viewmodel.ScannerItem;
import org.ossproject.desktop.viewmodel.ScannerViewModel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.ossproject.desktop.view.UiKit.*;

/** 실제 필터·정렬·페이지 분할을 수행하는 시장 스캐너 화면. */
public final class ScannerScreenView {
    private static final int PAGE_SIZE = 4;
    private final ScannerViewModel viewModel;
    private final Consumer<String> status;
    private final Consumer<String> openStock;

    public ScannerScreenView(ScannerViewModel viewModel, Consumer<String> status, Consumer<String> openStock) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.status = Objects.requireNonNull(status, "status");
        this.openStock = Objects.requireNonNull(openStock, "openStock");
    }

    public ScrollPane create() {
        Label title = heading("랭킹 · Market Scanner");
        ComboBox<String> market = new ComboBox<>(FXCollections.observableArrayList("국내 전체", "KOSPI", "KOSDAQ", "NASDAQ", "NYSE"));
        market.setValue("국내 전체");
        ComboBox<String> criterion = new ComboBox<>(FXCollections.observableArrayList(
                "거래량", "거래대금", "상승률", "하락률", "거래량 급증", "신고가", "신저가", "VI", "외국인", "기관"));
        criterion.setValue("거래량 급증");
        TextField minimum = new TextField("100000"); minimum.setPromptText("최소 거래량");
        Pagination pagination = new Pagination(); pagination.setAccessibleText("스캐너 결과 페이지"); pagination.setPrefHeight(480);
        AtomicReference<List<ScannerItem>> current = new AtomicReference<>(List.of());
        Runnable apply = () -> {
            long min;
            try { min = Long.parseLong(minimum.getText().replace(",", "").trim()); }
            catch (NumberFormatException invalid) { status.accept("최소 거래량은 숫자로 입력해주세요."); return; }
            List<ScannerItem> filtered = viewModel.filter(market.getValue(), criterion.getValue(), min);
            current.set(filtered);
            pagination.setPageCount(Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE));
            pagination.setCurrentPageIndex(0);
            pagination.setPageFactory(page -> page(current.get(), page));
            status.accept("스캐너 조건에 맞는 종목 " + filtered.size() + "건입니다.");
        };
        Button applyButton = primaryButton("필터 적용", apply);
        minimum.setOnAction(event -> apply.run());
        FlowPane filters = wrappingRow(10, labeledControl("시장", market), labeledControl("기준", criterion),
                labeledControl("최소 거래량", minimum), applyButton);
        filters.setAlignment(Pos.BOTTOM_LEFT); apply.run();
        return scrollPage("랭킹과 스캐너", new VBox(20, title, filters, pagination));
    }

    private Node page(List<ScannerItem> items, int page) {
        int from = Math.min(page * PAGE_SIZE, items.size()); int to = Math.min(from + PAGE_SIZE, items.size());
        TableView<ScannerItem> table = new TableView<>(FXCollections.observableArrayList(items.subList(from, to)));
        table.setAccessibleText("스캐너 결과 " + (page + 1) + "페이지");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getColumns().add(column("시장", ScannerItem::market));
        table.getColumns().add(column("종목", ScannerItem::name));
        table.getColumns().add(column("현재가", ScannerItem::price));
        table.getColumns().add(column("등락률", item -> String.format("%+.2f%%", item.changeRate())));
        table.getColumns().add(column("거래량", item -> String.format("%,d", item.volume())));
        table.getColumns().add(column("거래대금", item -> String.format("%,d백만원", item.tradingValueMillion())));
        table.getColumns().add(column("신호", ScannerItem::signal));
        table.setOnMouseClicked(event -> {
            ScannerItem selected = table.getSelectionModel().getSelectedItem();
            if (event.getClickCount() == 2 && selected != null) openStock.accept(selected.name());
        });
        table.setPrefHeight(380); return table;
    }

    private TableColumn<ScannerItem, String> column(String title, Function<ScannerItem, String> mapper) {
        TableColumn<ScannerItem, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new SimpleStringProperty(mapper.apply(data.getValue()))); return column;
    }
}
