package org.ossproject.desktop.view.screen;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.function.Supplier;

import static org.ossproject.desktop.view.UiKit.heading;
import static org.ossproject.desktop.view.UiKit.notConnectedPanel;
import static org.ossproject.desktop.view.UiKit.scrollPage;
import static org.ossproject.desktop.view.UiKit.tab;

/**
 * 미국주식 화면.
 *
 * <p>미국주식은 국내주식과 TR 체계가 완전히 다르다. 어댑터에 아직 구현하지 않았으므로
 * 값을 지어내지 않고 연동되지 않았다고 적는다. 특히 주문 화면은 실제로 주문을 보낼 수
 * 없는데 보낼 수 있는 것처럼 보이면 안 된다.
 *
 * <p>바깥 상태를 읽지 않는다. 연동이 시작되면 뷰모델을 받는 형태로 바뀐다.
 */
public final class UsMarketScreenView {

    /**
     * 관심종목 패널.
     *
     * <p>관심종목만은 사용자의 기록이라 실제 값을 보여 준다. 그 패널은 뷰모델을 알아야
     * 만들 수 있어서 바깥에서 받는다. 나머지 탭은 아직 연동 전이라 이 화면이 직접 만든다.
     */
    private final Supplier<VBox> watchlistPanel;

    public UsMarketScreenView(Supplier<VBox> watchlistPanel) {
        this.watchlistPanel = Objects.requireNonNull(watchlistPanel, "watchlistPanel");
    }

    public ScrollPane create() {
        Label title = heading("미국주식");
        Label delayed = new Label("미국 시세 · UI 데모 데이터"); delayed.getStyleClass().add("mode-badge");
        HBox titleRow = new HBox(12, title, delayed); titleRow.setAlignment(Pos.CENTER_LEFT);
        TabPane tabs = new TabPane(
                tab("시장", createUsHomePanel()), tab("종목", createUsStockPanel()),
                tab("스캐너", createUsScannerPanel()), tab("조건검색", createUsConditionPanel()),
                tab("관심종목", watchlistPanel.get()),
                tab("계좌", createUsAccountPanel()), tab("주문", createUsOrderPanel()),
                tab("리서치", createUsResearchPanel()), tab("환전", createFxPanel()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); tabs.setPrefHeight(620);
        return scrollPage("미국주식", new VBox(20, titleRow, tabs));
    }

    // 미국주식은 국내주식과 TR 체계가 완전히 다르다(usa*, ust*). 어댑터에 아직 구현하지
    // 않았으므로 화면에 값을 지어내지 않는다. 특히 주문 화면은 실제로 주문을 보낼 수 없는데
    // 보낼 수 있는 것처럼 보이면 안 된다.

    private VBox createUsHomePanel() {
        VBox panel = new VBox(18, notConnectedPanel("미국 지수와 랭킹",
                "usa10102 미국지수 리스트, usa20530 거래량상위, usa20540 거래대금상위"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsStockPanel() {
        VBox panel = new VBox(14, notConnectedPanel("미국 종목 상세",
                "usa20100 현재가 종목정보, usa20101 10호가, usa06012 일 차트"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsScannerPanel() {
        VBox panel = new VBox(16, notConnectedPanel("미국주식 스캐너",
                "usa20510 기간별 등락률상위, usa20530 거래량상위, usa24100 신고가/신저가"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsConditionPanel() {
        VBox panel = new VBox(16, notConnectedPanel("미국 조건검색",
                "usa20280 조건검색 목록조회, usa20281 조건검색 요청 일반"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createUsAccountPanel() {
        VBox panel = new VBox(16, notConnectedPanel("미국주식 계좌",
                "ust21070 원장잔고확인, ust21110 해외주식 예수금, ust21120 통화별 예수금"));
        panel.setPadding(new Insets(12));
        return panel;
    }


    private VBox createUsOrderPanel() {
        VBox panel = new VBox(notConnectedPanel("미국주식 주문",
                "ust20000 매수, ust20001 매도, ust20002 정정, ust20003 취소, ust21050 원장 미체결"));
        panel.setPadding(new Insets(18));
        return panel;
    }

    private VBox createUsResearchPanel() {
        VBox panel = new VBox(14, notConnectedPanel("미국주식 리서치", "usa24300 미국주식 리서치"));
        panel.setPadding(new Insets(12));
        return panel;
    }

    private VBox createFxPanel() {
        VBox panel = new VBox(16, notConnectedPanel("환전",
                "ust31301 환율조회, ust31300 환전 예상금액, ust31302 환전신청"));
        panel.setPadding(new Insets(12));
        return panel;
    }
}
