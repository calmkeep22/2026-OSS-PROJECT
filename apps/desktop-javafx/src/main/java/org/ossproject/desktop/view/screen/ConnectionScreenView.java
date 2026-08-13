package org.ossproject.desktop.view.screen;

import javafx.collections.ObservableList;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.viewmodel.ConnectionViewModel;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static org.ossproject.desktop.view.UiKit.*;

/** 키움 자격증명·토큰·계좌 상태 화면. */
public final class ConnectionScreenView {
    private final ConnectionViewModel viewModel;
    private final Consumer<String> status;

    public ConnectionScreenView(ConnectionViewModel viewModel, Consumer<String> status) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.status = Objects.requireNonNull(status, "status");
    }

    public ScrollPane create() {
        Label title = heading("키움증권 연결");
        Label description = new Label("App Key와 Secret으로 모의 또는 실전 서버 연결을 준비합니다. 이 UI 데모에서는 입력값을 외부로 전송하지 않습니다.");
        description.setWrapText(true); description.getStyleClass().add("muted-text");

        ToggleGroup environment = new ToggleGroup();
        RadioButton mock = new RadioButton("모의투자"); mock.setToggleGroup(environment); mock.setSelected(true);
        RadioButton real = new RadioButton("실전투자"); real.setToggleGroup(environment);
        mock.setUserData(ConnectionViewModel.Environment.MOCK); real.setUserData(ConnectionViewModel.Environment.LIVE);
        environment.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected != null) viewModel.environmentProperty().set((ConnectionViewModel.Environment) selected.getUserData());
        });

        TextField appKey = new TextField(); appKey.setPromptText("App Key");
        PasswordField appSecret = new PasswordField(); appSecret.setPromptText("App Secret");
        CheckBox rememberAlias = new CheckBox("암호화된 비밀 저장소의 별칭만 기억"); rememberAlias.setSelected(true);
        Label connectionState = stateBanner(viewModel.connectionMessageProperty().get(), viewModel.connectionToneProperty().get());
        connectionState.textProperty().bind(viewModel.connectionMessageProperty());
        viewModel.connectionToneProperty().addListener((obs, old, tone) ->
                connectionState.getStyleClass().setAll("label", "state-banner", "state-" + tone));
        Label tokenExpiry = new Label(); tokenExpiry.textProperty().bind(viewModel.tokenExpiryProperty());

        GridPane credentials = new GridPane(); credentials.setHgap(12); credentials.setVgap(12);
        credentials.add(wrappingRow(16, mock, real), 0, 0, 2, 1);
        credentials.add(new Label("App Key"), 0, 1); credentials.add(appKey, 1, 1);
        credentials.add(new Label("App Secret"), 0, 2); credentials.add(appSecret, 1, 2);
        credentials.add(rememberAlias, 0, 3, 2, 1); credentials.add(connectionState, 0, 4, 2, 1);
        GridPane.setHgrow(appKey, Priority.ALWAYS); GridPane.setHgrow(appSecret, Priority.ALWAYS);

        Button connect = primaryButton("연결 테스트", () -> status.accept(viewModel.testConnection(appKey.getText(), appSecret.getText())
                ? "연결 테스트 성공" : "자격증명을 입력해주세요."));
        Button issue = new Button("토큰 재발급"); issue.setOnAction(event -> {
            viewModel.reissueDemoToken(); status.accept("데모 토큰 만료 시각을 갱신했습니다.");
        });
        Button revoke = new Button("토큰 폐기"); revoke.setOnAction(event -> {
            viewModel.revokeToken(); status.accept("데모 토큰을 폐기했습니다.");
        });
        FlowPane actions = wrappingRow(8, connect, issue, revoke);
        VBox credentialCard = card("자격증명", credentials, actions);

        TableView<ObservableList<String>> accounts = textTable("키움 계좌 목록",
                List.of(row("선택", "모의계좌", "****-1204", "국내주식", "정상"),
                        row("", "미국주식 모의계좌", "****-7781", "미국주식", "정상")),
                "기본", "별칭", "계좌", "상품", "상태");
        accounts.setPrefHeight(190);
        Button select = new Button("선택 계좌를 기본으로 설정"); select.setOnAction(event -> {
            ObservableList<String> selected = accounts.getSelectionModel().getSelectedItem();
            if (selected == null) { status.accept("기본으로 설정할 계좌를 선택해주세요."); return; }
            viewModel.selectDefaultAccount(selected.get(1)); status.accept(selected.get(1) + "를 기본 계좌로 설정했습니다.");
        });
        VBox accountCard = card("계좌", accounts, select);
        VBox tokenCard = card("연결 정보", informationRow("REST", "정상"), informationRow("WebSocket", "연결됨"),
                informationRow("토큰 만료", tokenExpiry), informationRow("비밀 저장", "Windows DPAPI"));
        VBox body = new VBox(18, title, description, credentialCard, tokenCard, accountCard);
        return scrollPage("키움증권 연결", body);
    }
}
