package org.ossproject.desktop.view.screen;

import javafx.collections.ObservableList;
import org.ossproject.desktop.presentation.Formatters;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.state.JournalEntry;
import org.ossproject.desktop.viewmodel.AccountScreenData;
import org.ossproject.finance.model.Account;
import org.ossproject.finance.model.Deposits;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.Position;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.ossproject.desktop.presentation.Formatters.assetsSource;
import static org.ossproject.desktop.presentation.Formatters.orderTime;
import static org.ossproject.desktop.presentation.Formatters.signedWon;
import static org.ossproject.desktop.presentation.Formatters.won;
import static org.ossproject.desktop.view.UiKit.*;

/**
 * 계좌 화면.
 *
 * <p>예수금을 단계별로 나눠 보여 준다. "지금 얼마 주문할 수 있나" 와 "지금 얼마 뽑을 수
 * 있나" 와 "결제가 끝나면 얼마 남나" 는 서로 다른 질문이고 답도 다르다. 미수가 나면
 * 감추지 않고 반대매매 위험을 문장으로 알린다.
 *
 * <p>계좌 값은 애플리케이션 계층이 조회해 {@link AccountScreenData} 로 넘겨준다. 이 화면은
 * 스스로 조회하지 않는다.
 */
public final class AccountScreenView {

    private final Supplier<ObservableList<JournalEntry>> journalEntries;
    private final Consumer<TableView<ObservableList<String>>> openStock;
    private final BiConsumer<TableView<ObservableList<String>>, OrderSide> tradeStock;
    private final Consumer<String> status;
    /** 매매일지 작성과 삭제는 대화상자와 상태 저장이 필요해 앱이 맡는다. */
    private final Consumer<JournalEntry> onEditJournal;
    private final Consumer<TableView<JournalEntry>> onDeleteJournal;

    public AccountScreenView(Supplier<ObservableList<JournalEntry>> journalEntries,
                             Consumer<TableView<ObservableList<String>>> openStock,
                             BiConsumer<TableView<ObservableList<String>>, OrderSide> tradeStock,
                             Consumer<String> status,
                             Consumer<JournalEntry> onEditJournal,
                             Consumer<TableView<JournalEntry>> onDeleteJournal) {
        this.journalEntries = Objects.requireNonNull(journalEntries, "journalEntries");
        this.openStock = Objects.requireNonNull(openStock, "openStock");
        this.tradeStock = Objects.requireNonNull(tradeStock, "tradeStock");
        this.status = Objects.requireNonNull(status, "status");
        this.onEditJournal = Objects.requireNonNull(onEditJournal, "onEditJournal");
        this.onDeleteJournal = Objects.requireNonNull(onDeleteJournal, "onDeleteJournal");
    }

    public ScrollPane create(AccountScreenData data) {
        Account snapshot = data.account();
        Label title = heading("계좌");
        // 계좌번호는 접근 토큰에 연결된 것을 그대로 보여 준다. 목록을 지어내지 않는다.
        Label accountNo = new Label("모의계좌 " + snapshot.maskedAccountNo());
        accountNo.getStyleClass().add("status-chip");
        accountNo.setAccessibleText("조회 중인 계좌. 모의계좌 " + snapshot.maskedAccountNo());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, accountNo); header.setAlignment(Pos.CENTER_LEFT);

        // 값은 모의주문 엔진이 들고 있는 실제 계좌 상태에서 읽는다. 화면이 따로 계산하거나
        // 예시 숫자를 적어 두지 않는다.
        FlowPane metrics = wrappingRow(14,
                summaryCard("총 평가자산", Formatters.won(snapshot.totalAssets()),
                        assetsSource(snapshot), "neutral"),
                summaryCard("평가손익", signedWon(snapshot.totalProfitLoss()),
                        "평가금액 " + Formatters.won(snapshot.totalMarketValue())
                                + " · " + assetsSource(snapshot),
                        snapshot.totalProfitLoss().signum() >= 0 ? "positive" : "negative"),
                summaryCard("예수금", Formatters.won(snapshot.deposits().cash()),
                        "주문 가능 " + Formatters.won(snapshot.deposits().orderable()), "neutral"));

        TableView<ObservableList<String>> holdings = textTable("보유종목 표",
                snapshot.positions().stream().map(position -> row(
                        position.name(),
                        position.quantity() + "",
                        Formatters.won(position.averagePrice()),
                        Formatters.won(position.currentPrice()),
                        Formatters.won(position.marketValue()),
                        signedWon(position.profitLoss()),
                        position.profitLossRate().toPlainString() + "%")).toList(),
                "종목", "수량", "평균단가", "현재가", "평가금액", "손익", "수익률");
        holdings.setPrefHeight(300);
        holdings.setOnMouseClicked(event -> { if (event.getClickCount() == 2) openStock.accept(holdings); });
        holdings.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) openStock.accept(holdings); });
        Button holdingDetail = new Button("선택 종목 상세"); holdingDetail.setOnAction(event -> openStock.accept(holdings));
        Button holdingBuy = primaryButton("선택 종목 매수", () -> tradeStock.accept(holdings, OrderSide.BUY));
        Button holdingSell = new Button("선택 종목 매도"); holdingSell.setOnAction(event -> tradeStock.accept(holdings, OrderSide.SELL));
        VBox holdingsPanel = new VBox(10, holdings, wrappingRow(8, holdingDetail, holdingBuy, holdingSell));
        holdingsPanel.setPadding(new Insets(10));

        // 실제 증권사 화면처럼 예수금을 단계별로 나눠 보여 준다. "지금 얼마 주문할 수 있나",
        // "지금 얼마 뽑을 수 있나", "결제가 끝나면 얼마 남나" 는 서로 다른 질문이다.
        Deposits deposits = snapshot.deposits();
        VBox cash = new VBox(12,
                informationRow("예수금", Formatters.won(deposits.cash())),
                informationRow("D+2 추정예수금", signedWon(deposits.settledCash())),
                informationRow("주문 대기 금액", Formatters.won(snapshot.balance().locked())),
                informationRow("주문 가능 금액", Formatters.won(deposits.orderable())),
                informationRow("출금 가능 금액", Formatters.won(deposits.withdrawable())));
        if (deposits.hasShortfall()) {
            cash.getChildren().add(shortfallWarning(deposits));
        }
        cash.setPadding(new Insets(20));

        TableView<ObservableList<String>> open = orderStatusTable(true, data.orders());
        TableView<ObservableList<String>> fills = orderStatusTable(false, data.orders());
        TableView<ObservableList<String>> history = textTable("주문내역",
                data.orders().stream().map(order -> row(
                        orderTime(order), order.name(), order.side().displayName(),
                        order.limitPrice() == null ? "시장가" : Formatters.won(order.limitPrice()),
                        Long.toString(order.quantity()), order.status().displayName())).toList(),
                "시간", "종목", "구분", "주문가", "수량", "상태");
        history.setPlaceholder(new Label("주문 내역이 없습니다."));
        TableView<JournalEntry> journal = typedTable("매매일지", journalEntries.get(),
                textColumn("날짜", JournalEntry::date),
                textColumn("종목", JournalEntry::securityName),
                textColumn("매수금액", JournalEntry::buyAmount),
                textColumn("매도금액", JournalEntry::sellAmount),
                textColumn("손익", JournalEntry::profitLoss),
                textColumn("전략·메모", JournalEntry::memo),
                textColumn("태그", JournalEntry::tags));
        Button addJournal = new Button("일지 작성"); addJournal.setOnAction(event -> onEditJournal.accept(null));
        Button editJournal = new Button("선택 수정"); editJournal.setOnAction(event -> {
            JournalEntry selected = journal.getSelectionModel().getSelectedItem();
            if (selected == null) {
                status.accept("수정할 매매일지를 선택해주세요.");
                journal.requestFocus();
                return;
            }
            onEditJournal.accept(selected);
        });
        Button deleteJournal = new Button("선택 삭제");
        deleteJournal.setOnAction(event -> onDeleteJournal.accept(journal));
        Button attach = new Button("차트 화면 첨부"); attach.setOnAction(event -> status.accept("현재 차트 화면을 매매일지 첨부 대상으로 선택했습니다."));
        VBox journalPanel = new VBox(10, journal, wrappingRow(8, addJournal, editJournal, deleteJournal, attach));
        journalPanel.setPadding(new Insets(10));

        TabPane tabs = new TabPane(
                tab("보유종목", holdingsPanel), tab("예수금", cash), tab("미체결", open),
                tab("체결", fills), tab("주문내역", history), tab("매매일지", journalPanel));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(390);
        VBox body = new VBox(20, header, metrics, tabs);
        return scrollPage("계좌 대시보드", body);
    }


    /**
     * 미수 발생 경고.
     *
     * <p>D+2 예수금이 음수면 결제일까지 채워 넣지 않는 한 반대매매 대상이 된다. 색으로만
     * 알리면 화면을 볼 수 없는 사용자에게 전달되지 않으므로 문장으로 남긴다.
     */
    private static javafx.scene.Node shortfallWarning(Deposits deposits) {
        String text = "미수금 " + won(deposits.shortfall())
                + "이 발생했습니다. 결제일까지 입금하지 않으면 반대매매가 될 수 있습니다.";
        Label warning = new Label(text);
        warning.getStyleClass().add("safety-note");
        warning.setWrapText(true);
        warning.setAccessibleText(text);
        return warning;
    }
}
