package org.ossproject.desktop.view.screen;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.ossproject.desktop.navigation.Screen;
import org.ossproject.ai.MarketIndex;
import org.ossproject.desktop.presentation.Formatters;
import org.ossproject.finance.model.account.Account;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.ossproject.desktop.presentation.Formatters.signedWon;
import static org.ossproject.desktop.view.UiKit.sectionHeading;

/** Builds and asynchronously loads the investment home screen. */
public final class DashboardScreenView {
    private final Supplier<Account> account;
    private final Supplier<List<MarketIndex>> market;
    private final BiConsumer<String, String> speak;
    private final Function<Screen, Node> navigationIcon;
    private final Consumer<Screen> navigate;

    public DashboardScreenView(Supplier<Account> account,
                               Supplier<List<MarketIndex>> market,
                               BiConsumer<String, String> speak,
                               Function<Screen, Node> navigationIcon,
                               Consumer<Screen> navigate) {
        this.account = Objects.requireNonNull(account, "account");
        this.market = Objects.requireNonNull(market, "market");
        this.speak = Objects.requireNonNull(speak, "speak");
        this.navigationIcon = Objects.requireNonNull(navigationIcon, "navigationIcon");
        this.navigate = Objects.requireNonNull(navigate, "navigate");
    }

    public VBox create() {
        Label loading = new Label("시장 지표를 준비하고 있습니다.");
        ProgressIndicator progress = new ProgressIndicator();
        VBox host = new VBox(12, progress, loading);
        host.setAlignment(Pos.CENTER);
        host.getStyleClass().addAll("screen-content", "dashboard-screen");
        // 시장 지표는 앱 시작과 함께 한 번 준비한다. 홈을 열 때마다 계좌와 지표를 다시
        // 조회하면 화면 이동만으로도 지연이 생긴다. 계좌는 화면에 표시하지 않고,
        // 사용자가 홈 요약을 요청했을 때만 조회한다.
        CompletableFuture<List<MarketIndex>> marketLoad = CompletableFuture.supplyAsync(market)
                .handle((indices, failure) ->
                        failure == null && indices != null ? indices : List.<MarketIndex>of());
        marketLoad.thenAccept(indices ->
                Platform.runLater(() -> {
                    ScrollPane page = content(indices);
                    host.getChildren().setAll(page);
                    VBox.setVgrow(page, Priority.ALWAYS);
                    host.setAlignment(Pos.TOP_LEFT);
                }));
        return host;
    }

    private ScrollPane content(List<MarketIndex> indices) {
        Button listen = new Button("홈 요약 듣기");
        listen.setOnAction(event -> {
            listen.setDisable(true);
            CompletableFuture.supplyAsync(account)
                    .handle((snapshot, failure) -> failure == null ? snapshot : null)
                    .thenAccept(snapshot -> Platform.runLater(() -> {
                        speak.accept(summaryText(snapshot, indices), "dashboard-summary");
                        listen.setDisable(false);
                    }));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, spacer, listen);
        header.setAlignment(Pos.CENTER_LEFT);

        GridPane marketState = new GridPane();
        marketState.setHgap(10);
        marketState.setMinHeight(Region.USE_PREF_SIZE);
        marketState.getColumnConstraints().addAll(equalColumn(), equalColumn(), equalColumn());
        // 지수와 환율을 뺐다. 그 자리에 내 계좌의 세 값을 놓는다 — 홈에서 가장 먼저
        // 알고 싶은 것은 "지금 얼마를 가지고 있고, 얼마를 벌고 있고, 얼마를 쓸 수
        // 있나" 다. 코스피는 그 다음이고, 홈 요약 듣기에서 그대로 들을 수 있다.
        VBox totalCard = statusCard("계좌 총자산", "조회 중", "증권사 응답 대기", "neutral");
        VBox profitCard = statusCard("평가손익", "조회 중", "보유 종목 기준", "neutral");
        VBox orderableCard = statusCard("주문 가능 금액", "조회 중", "예수금 기준", "neutral");
        marketState.add(totalCard, 0, 0);
        marketState.add(profitCard, 1, 0);
        marketState.add(orderableCard, 2, 0);
        fillAccountCards(totalCard, profitCard, orderableCard);

        GridPane features = new GridPane();
        features.setHgap(10);
        features.setVgap(10);
        features.setMinHeight(Region.USE_PREF_SIZE);
        features.getColumnConstraints().addAll(equalColumn(), equalColumn(), equalColumn());
        features.add(featureCard("종목 찾기", "종목명·코드 검색", Screen.SEARCH), 0, 0);
        features.add(featureCard("계좌", "자산·예수금·주문 현황", Screen.ACCOUNT), 1, 0);
        features.add(featureCard("이상 감지", "실시간 가격·거래량 신호", Screen.ANOMALY), 2, 0);
        features.add(featureCard("관심종목", "내 종목을 빠르게 확인", Screen.WATCHLIST), 0, 1);
        features.add(featureCard("청각 차트", "시세를 소리로 탐색", Screen.RADIO), 1, 1);
        features.add(featureCard("AI 챗봇", "분석·뉴스에 관해 질문", Screen.CHAT), 2, 1);
        // 일곱 번째 칸을 두면 세 번째 줄이 생겨 창 아래로 잘린다. 설정은 왼쪽 레일의
        // 톱니 단추와 "설정 열어줘" 음성 명령으로 그대로 갈 수 있어 길이 끊기지 않는다.

        VBox dashboard = new VBox(12, header, marketState,
                sectionHeading("주요 기능"), features);
        dashboard.getStyleClass().add("dashboard-shell");
        dashboard.setMaxWidth(980);
        dashboard.setMinHeight(Region.USE_PREF_SIZE);
        StackPane centered = new StackPane(dashboard);
        centered.setAlignment(Pos.TOP_CENTER);
        centered.setMinHeight(Region.USE_PREF_SIZE);
        VBox body = new VBox(centered);
        body.getStyleClass().addAll("screen-content", "dashboard-screen");
        body.setPadding(new Insets(18));
        body.setMinHeight(Region.USE_PREF_SIZE);

        ScrollPane page = new ScrollPane(body);
        page.setFitToWidth(true);
        page.setFitToHeight(false);
        page.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        page.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        page.getStyleClass().add("workspace-scroll");
        page.setAccessibleText("홈 화면");
        org.ossproject.desktop.view.UiKit.useBrowserLikeScrolling(page);
        return page;
    }

    private static ColumnConstraints equalColumn() {
        ColumnConstraints column = new ColumnConstraints();
        column.setPercentWidth(33.333);
        column.setHgrow(Priority.ALWAYS);
        column.setFillWidth(true);
        return column;
    }

    /**
     * 계좌에서 온 두 카드를 채운다.
     *
     * <p>계좌 조회는 증권사를 다녀오므로 화면을 만드는 자리에서 기다리면 홈이 그만큼
     * 늦게 뜬다. 먼저 "조회 중" 으로 그려 두고 값이 오면 갈아 끼운다.
     *
     * <p>못 받으면 0 을 쓰지 않는다. 0원과 "못 받았다" 는 전혀 다른 말인데, 화면을 볼
     * 수 없는 사용자는 그 둘을 구별할 방법이 없다.
     */
    private void fillAccountCards(VBox totalCard, VBox profitCard, VBox orderableCard) {
        CompletableFuture.supplyAsync(account)
                .handle((snapshot, failure) -> failure == null ? snapshot : null)
                .thenAccept(snapshot -> Platform.runLater(() -> {
                    if (snapshot == null) {
                        replaceCard(totalCard, "계좌 총자산", "받지 못했습니다",
                                "계좌 조회 후 표시합니다.", "neutral");
                        replaceCard(profitCard, "평가손익", "받지 못했습니다",
                                "계좌 조회 후 표시합니다.", "neutral");
                        replaceCard(orderableCard, "주문 가능 금액", "받지 못했습니다",
                                "계좌 조회 후 표시합니다.", "neutral");
                        return;
                    }
                    // 증권사가 준 값인지 우리가 더한 값인지 적어 둔다. 이 앱에서 그
                    // 구별은 값 자체만큼 중요하다.
                    replaceCard(totalCard, "계좌 총자산", Formatters.won(snapshot.totalAssets()),
                            snapshot.totalAssetsReportedByBroker()
                                    ? "증권사 제공 값" : "보유 종목과 예수금 합계", "neutral");
                    java.math.BigDecimal profit = snapshot.totalProfitLoss();
                    // 색과 함께 부호를 글로도 남긴다. 색만으로는 색을 못 보는 사용자에게
                    // 아무것도 전해지지 않는다.
                    String sign = profit.signum() > 0 ? "+" : "";
                    String tone = switch (profit.signum()) {
                        case 1 -> "positive";
                        case -1 -> "negative";
                        default -> "neutral";
                    };
                    replaceCard(profitCard, "평가손익", sign + Formatters.won(profit),
                            "보유 종목 기준", tone);
                    replaceCard(orderableCard, "주문 가능 금액",
                            Formatters.won(snapshot.balance().available()), "예수금 기준", "neutral");
                }));
    }

    /** 자리를 지키며 내용만 바꾼다. 카드를 새로 만들면 격자에서 자리가 흔들린다. */
    private void replaceCard(VBox card, String title, String value, String detail, String tone) {
        VBox fresh = statusCard(title, value, detail, tone);
        card.getChildren().setAll(fresh.getChildren());
        card.getStyleClass().setAll(fresh.getStyleClass());
        card.setAccessibleText(title + ", " + value + ", " + detail);
    }

    /**
     * 지표 카드 한 장.
     *
     * <p>받지 못한 지표는 빈 카드로 둔다. 직전 값이나 0 을 채우지 않는다 — 화면을 볼 수
     * 없는 사용자는 채운 값과 받은 값을 구별할 방법이 없다.
     */
    private VBox indexCard(String group, List<MarketIndex> indices) {
        Optional<MarketIndex> found = indices.stream()
                .filter(index -> group.equals(index.group())).findFirst();
        if (found.isEmpty()) {
            VBox empty = statusCard(group, "받지 못했습니다",
                    "시장 데이터 수신 후 표시합니다.", "neutral");
            empty.setAccessibleText(group + ", 아직 받지 못했습니다.");
            return empty;
        }
        MarketIndex index = found.get();
        // 색과 함께 "상승"·"하락" 을 글로도 적는다. 색만으로 방향을 전하면 색을 못 보는
        // 사용자에게는 아무 정보도 남지 않는다.
        String tone = switch (index.changePercent().map(java.math.BigDecimal::signum).orElse(0)) {
            case 1 -> "positive";
            case -1 -> "negative";
            default -> "neutral";
        };
        VBox card = statusCard(group, index.name() + " " + index.formattedValue(),
                index.changeText() + " · " + index.asOfText(), tone);
        card.setAccessibleText(group + ". " + index.spoken());
        return card;
    }

    /** 읽어 줄 요약. 받은 것만 말하고, 못 받은 것은 못 받았다고 말한다. */
    private String summaryText(Account snapshot, List<MarketIndex> indices) {
        StringBuilder spoken = new StringBuilder();
        if (indices.isEmpty()) {
            spoken.append("시장 지표를 받지 못했습니다. ");
        } else {
            for (MarketIndex index : indices) {
                spoken.append(index.spoken()).append(' ');
            }
        }
        if (snapshot == null) {
            spoken.append("계좌는 아직 조회하지 못했습니다.");
        } else {
            spoken.append("총 자산 ").append(Formatters.won(snapshot.totalAssets()))
                    .append(", 평가손익은 ").append(signedWon(snapshot.totalProfitLoss()))
                    .append(", 주문 가능 금액은 ")
                    .append(Formatters.won(snapshot.deposits().orderable())).append(" 입니다.");
        }
        return spoken.toString();
    }

    private static VBox statusCard(String title, String value, String detail, String tone) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dashboard-card-title");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("dashboard-card-value");
        valueLabel.setWrapText(true);
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("muted-text");
        detailLabel.setWrapText(true);
        VBox card = new VBox(5, titleLabel, valueLabel, detailLabel);
        card.getStyleClass().addAll("dashboard-status-card", "dashboard-tone-" + tone);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private Button featureCard(String title, String detail, Screen screen) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dashboard-feature-title");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("dashboard-feature-detail");
        detailLabel.setWrapText(true);
        detailLabel.setMaxWidth(Double.MAX_VALUE);
        VBox copy = new VBox(3, titleLabel, detailLabel);
        copy.setAlignment(Pos.CENTER);
        Node icon = navigationIcon.apply(screen);
        icon.getStyleClass().add("dashboard-feature-icon");
        VBox graphic = new VBox(9, icon, copy);
        graphic.setAlignment(Pos.CENTER);
        graphic.setMaxWidth(Double.MAX_VALUE);
        Button button = new Button();
        button.setGraphic(graphic);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMaxHeight(Double.MAX_VALUE);
        button.getStyleClass().add("dashboard-feature-card");
        button.setAccessibleText(title + ". " + detail + ". 화면 열기");
        button.setOnAction(event -> navigate.accept(screen));
        return button;
    }
}
