package org.ossproject.desktop.view.screen;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.viewmodel.ConnectionViewModel;
import org.ossproject.finance.model.account.Account;
import org.ossproject.secret.SecretBytes;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.ossproject.desktop.view.UiKit.*;

/** 키움 자격증명·토큰·계좌 상태 화면. */
public final class ConnectionScreenView {
    private final ConnectionViewModel viewModel;
    private final Consumer<String> status;
    private final Supplier<Account> accountSupplier;
    private final ObservableValue<String> realtimeState;
    private final String dataSource;

    public ConnectionScreenView(ConnectionViewModel viewModel, Consumer<String> status,
                                Supplier<Account> accountSupplier,
                                ObservableValue<String> realtimeState, String dataSource) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.status = Objects.requireNonNull(status, "status");
        this.accountSupplier = Objects.requireNonNull(accountSupplier, "accountSupplier");
        this.realtimeState = Objects.requireNonNull(realtimeState, "realtimeState");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public ScrollPane create() {
        Label title = heading("키움증권 연결");
        Label description = new Label("App Key와 Secret으로 키움 모의투자 서버의 토큰과 계좌 조회를 실제 확인합니다. "
                + "입력값은 연결 확인을 위해 키움 API에만 사용하며, 저장을 선택하면 현재 Windows 사용자 계정의 DPAPI로 보호합니다.");
        description.setWrapText(true); description.getStyleClass().add("muted-text");

        TextField appKey = new TextField(); appKey.setPromptText("App Key");
        PasswordField appSecret = new PasswordField(); appSecret.setPromptText("App Secret");
        CheckBox rememberCredentials = new CheckBox("보호된 비밀 저장소에 자격증명 저장");
        rememberCredentials.setSelected(viewModel.isCredentialStorageAvailable());
        rememberCredentials.setDisable(!viewModel.isCredentialStorageAvailable());
        rememberCredentials.setAccessibleHelp("선택하면 App Key와 App Secret을 평문이 아닌 운영체제 보호 저장소에 저장합니다.");
        Label connectionState = stateBanner(viewModel.connectionMessageProperty().get(), viewModel.connectionToneProperty().get());
        connectionState.textProperty().bind(viewModel.connectionMessageProperty());
        viewModel.connectionToneProperty().addListener((obs, old, tone) ->
                connectionState.getStyleClass().setAll("label", "state-banner", "state-" + tone));
        Label credentialStorageStatus = new Label();
        credentialStorageStatus.textProperty().bind(viewModel.credentialStorageStatusProperty());
        credentialStorageStatus.setWrapText(true);
        Label credentialStorageDescription = new Label();
        credentialStorageDescription.textProperty().bind(viewModel.credentialStorageDescriptionProperty());
        credentialStorageDescription.setWrapText(true);

        GridPane credentials = new GridPane(); credentials.setHgap(12); credentials.setVgap(12);
        credentials.add(stateBanner("키움 모의투자 전용 · 실전 주문은 지원하지 않습니다.", "warning"), 0, 0, 2, 1);
        credentials.add(new Label("App Key"), 0, 1); credentials.add(appKey, 1, 1);
        credentials.add(new Label("App Secret"), 0, 2); credentials.add(appSecret, 1, 2);
        credentials.add(rememberCredentials, 0, 3, 2, 1);
        credentials.add(credentialStorageStatus, 0, 4, 2, 1);
        credentials.add(connectionState, 0, 5, 2, 1);
        GridPane.setHgrow(appKey, Priority.ALWAYS); GridPane.setHgrow(appSecret, Priority.ALWAYS);

        Button connect = new Button("자격증명 저장");
        connect.getStyleClass().add("primary-button");
        connect.setOnAction(event -> {
            char[] secret = appSecret.getText().toCharArray();
            String key = appKey.getText();
            boolean remember = rememberCredentials.isSelected();
            connect.setDisable(true);
            status.accept("키움 모의투자 토큰과 계좌를 확인하고 있습니다.");
            appSecret.clear();
            CompletableFuture.supplyAsync(() -> {
                try {
                    return viewModel.testConnection(key, secret, remember);
                } finally {
                    SecretBytes.wipe(secret);
                }
            }).whenComplete((success, failure) -> Platform.runLater(() -> {
                connect.setDisable(false);
                status.accept(failure == null && Boolean.TRUE.equals(success)
                        ? "키움 모의투자 연결을 확인했습니다. 앱을 다시 시작하면 적용됩니다."
                        : failure == null ? viewModel.connectionMessageProperty().get()
                        : "키움 모의투자 연결을 확인하지 못했습니다.");
            }));
        });
        Button useStored = new Button("저장된 자격증명 사용");
        useStored.disableProperty().bind(viewModel.storedCredentialsProperty().not());
        useStored.setOnAction(event -> {
            status.accept("저장된 자격증명으로 키움 모의투자 연결을 확인하고 있습니다.");
            CompletableFuture.supplyAsync(() -> viewModel.testConnection("", new char[0], false))
                    .whenComplete((success, failure) -> Platform.runLater(() -> {
                        status.accept(failure == null && Boolean.TRUE.equals(success)
                                ? "저장된 자격증명을 확인했습니다. 앱을 다시 시작하면 적용됩니다."
                                : viewModel.connectionMessageProperty().get());
                    }));
        });
        Button deleteStored = new Button("저장된 자격증명 삭제");
        deleteStored.disableProperty().bind(viewModel.storedCredentialsProperty().not());
        deleteStored.setOnAction(event -> {
            boolean deleted = viewModel.deleteStoredCredentials();
            status.accept(deleted ? "저장된 자격증명을 삭제했습니다." : viewModel.connectionMessageProperty().get());
        });
        FlowPane actions = wrappingRow(8, connect, useStored, deleteStored);
        VBox credentialCard = card("자격증명", credentials, actions);

        String accountMessage = "키움 모의계좌를 조회하고 있습니다.";
        TableView<ObservableList<String>> accounts = textTable("키움 실제 응답 계좌", List.of(),
                "선택", "구분", "계좌", "상품", "상태");
        accounts.setPrefHeight(190);
        accounts.setPlaceholder(new Label(accountMessage));
        Label accountDescription = new Label(accountMessage); accountDescription.setWrapText(true);
        CompletableFuture.supplyAsync(accountSupplier).whenComplete((account, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        accountDescription.setText("키움 API에서 계좌를 조회하지 못했습니다. 자격증명과 연결 상태를 확인해주세요.");
                        accounts.setPlaceholder(new Label(accountDescription.getText()));
                    } else {
                        accounts.getItems().setAll(java.util.Collections.singletonList(
                                javafx.collections.FXCollections.observableArrayList(
                                        row("자동", "키움 연결 계좌", account.maskedAccountNo(), "국내주식", "조회됨"))));
                        accountDescription.setText("키움 API가 반환한 계좌입니다. 계좌번호는 자동으로 조회하며 화면에는 마스킹합니다.");
                    }
                }));
        VBox accountCard = card("계좌", accountDescription, accounts);
        Label liveState = new Label(); liveState.textProperty().bind(realtimeState);
        VBox tokenCard = card("실행 연결 정보", informationRow("시세·계좌 공급원", dataSource),
                labeledControl("실시간", liveState),
                informationRow("보호 수준", credentialStorageDescription));
        VBox body = new VBox(18, title, description, credentialCard, tokenCard, accountCard);
        return scrollPage("키움증권 연결", body);
    }
}
