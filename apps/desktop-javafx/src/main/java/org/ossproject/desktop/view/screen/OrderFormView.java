package org.ossproject.desktop.view.screen;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.ossproject.desktop.navigation.OrderDraft;
import org.ossproject.desktop.viewmodel.OrderDraftViewModel;
import org.ossproject.finance.model.OrderSide;
import org.ossproject.finance.model.order.OrderType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.ossproject.desktop.view.UiKit.*;

/**
 * 모의주문 폼.
 *
 * <p>되돌릴 수 없는 동작 직전의 화면이다. 그래서 여기서는 두 가지를 지킨다 — 조작 요소에
 * 빠짐없이 이름을 달고, 값을 못 구했을 때 0 으로 채우지 않는다. 0 원 · 0 주는 사용자가
 * 읽을 수 있는 값처럼 보이지만 사실은 "모른다" 는 뜻이다.
 *
 * <p>초안과 수량 셈은 {@link OrderDraftViewModel} 이 맡는다. 화면 안에 두면 "10퍼센트를
 * 눌렀을 때 몇 주인가" 를 검사할 수 없다.
 *
 * <p>종목은 여기서 바꾸지 않는다. 주문 화면에서 종목을 갈아 끼울 수 있으면, 값을 채워
 * 놓고 종목만 바꿔 엉뚱한 주문을 내기 쉽다.
 */
public final class OrderFormView {

    private static final List<Integer> RATIOS = List.of(10, 25, 50, 100);

    private final OrderDraftViewModel viewModel;
    /** 금액을 사람이 읽는 글자로. 종목 통화에 따라 달라져 앱에서 받는다. */
    private final Function<BigDecimal, String> formatMoney;
    private final Consumer<OrderDraft> onPreview;
    private final Consumer<String> onStatus;
    /** 초안이 바뀔 때마다 알린다. 주문 화면을 떠났다 돌아와도 값이 남아야 한다. */
    private final Consumer<OrderDraft> onDraftChanged;
    /** 같은 주문을 연달아 내지 못하게 막을지. 설정에서 온다. */
    private final boolean preventDuplicates;

    public OrderFormView(OrderDraftViewModel viewModel, boolean preventDuplicates,
                         Function<BigDecimal, String> formatMoney,
                         Consumer<OrderDraft> onPreview, Consumer<String> onStatus,
                         Consumer<OrderDraft> onDraftChanged) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.preventDuplicates = preventDuplicates;
        this.formatMoney = Objects.requireNonNull(formatMoney, "formatMoney");
        this.onPreview = Objects.requireNonNull(onPreview, "onPreview");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.onDraftChanged = Objects.requireNonNull(onDraftChanged, "onDraftChanged");
    }

    public VBox create() {
        OrderDraft draft = viewModel.draft();

        TextField symbol = readOnlyField("종목 코드", draft.symbol());
        TextField name = readOnlyField("종목명", draft.name());
        ComboBox<OrderSide> side = sideBox(draft.side());
        ComboBox<OrderType> orderType = typeBox(draft.type());
        Spinner<Integer> quantity = new Spinner<>(1, 1_000_000, draft.quantity());
        quantity.setEditable(true);
        quantity.setAccessibleText("주문 수량");
        // 스피너에 이름을 달아도 초점은 안쪽 편집기가 받는다. 편집기에 이름이 없으면
        // 스크린리더는 "편집" 이라고만 읽는다.
        quantity.getEditor().setAccessibleText("주문 수량");
        TextField price = new TextField(draft.price());
        price.setAccessibleText("주문 가격");
        price.setDisable(draft.type() == OrderType.MARKET);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        field(form, 0, 0, "종목 코드", symbol);
        field(form, 2, 0, "종목명", name);
        field(form, 0, 1, "매수 / 매도", side);
        field(form, 2, 1, "주문 유형", orderType);
        field(form, 0, 2, "가격", price);
        field(form, 2, 2, "수량", quantity);

        Label estimated = new Label();
        Label orderable = new Label("모의계좌 조회 중");
        Runnable refresh = () -> {
            viewModel.update(side.getValue(), orderType.getValue(), quantity.getValue(),
                    price.getText());
            estimated.setText(viewModel.estimatedAmount(price.getText(), quantity.getValue())
                    .map(formatMoney).orElse("가격을 확인하세요"));
            onDraftChanged.accept(viewModel.draft());
        };

        side.valueProperty().addListener((observable, old, value) -> refresh.run());
        orderType.valueProperty().addListener((observable, old, value) -> {
            // 시장가는 가격을 받지 않는다. 칸을 열어 두면 적어 넣고 반영됐다고 읽는다.
            price.setDisable(value == OrderType.MARKET);
            refresh.run();
        });
        price.textProperty().addListener((observable, old, value) -> refresh.run());
        quantity.valueProperty().addListener((observable, old, value) -> refresh.run());
        refresh.run();

        List<Button> ratioButtons = ratioButtons(side, orderType, price, quantity);
        HBox ratioRow = new HBox(10, new Label("주문 비율"), new HBox(8, ratioButtons.toArray(Button[]::new)));
        ratioRow.setAlignment(Pos.CENTER_LEFT);

        VBox estimates = new VBox(4,
                informationRow("주문 예상금액", estimated),
                informationRow("주문 가능금액", orderable));
        estimates.getStyleClass().add("estimate-box");
        estimates.setPadding(new Insets(8));

        VBox box = new VBox(8, sectionHeading("모의주문 준비"), form, ratioRow, estimates,
                previewButton());
        box.getStyleClass().addAll("panel-card", "order-form-compact");
        box.setPadding(new Insets(12));
        box.setMaxHeight(Double.MAX_VALUE);
        this.orderableLabel = orderable;
        this.ratios = ratioButtons;
        return box;
    }

    private Label orderableLabel;
    private List<Button> ratios = List.of();

    /**
     * 계좌가 도착했다.
     *
     * <p>그전까지 비율 단추는 눌리지 않는다. 계좌를 모르면 몇 주를 살 수 있는지도 모르고,
     * 그 상태에서 수량을 채우면 지어낸 값이 된다.
     */
    public void accountLoaded() {
        if (orderableLabel != null) {
            orderableLabel.setText(viewModel.orderableAmount().map(formatMoney)
                    .orElse("계좌 조회 실패"));
        }
        ratios.forEach(button -> button.setDisable(!viewModel.hasAccount()));
    }

    /** 계좌를 못 받았다. 금액 자리를 비워 두지 않고 그 사실을 적는다. */
    public void accountFailed() {
        if (orderableLabel != null) {
            orderableLabel.setText("계좌 조회 실패");
        }
        ratios.forEach(button -> button.setDisable(true));
    }

    private List<Button> ratioButtons(ComboBox<OrderSide> side, ComboBox<OrderType> orderType,
                                      TextField price, Spinner<Integer> quantity) {
        List<Button> buttons = new ArrayList<>();
        for (int percent : RATIOS) {
            Button button = new Button(percent + "%");
            button.setDisable(true);
            button.setAccessibleText("주문 가능 수량의 " + percent + "퍼센트로 채우기");
            button.setOnAction(event -> {
                OrderDraftViewModel.Suggestion suggestion = viewModel.quantityFor(
                        percent, side.getValue(), orderType.getValue(), price.getText());
                if (!suggestion.available()) {
                    onStatus.accept(suggestion.reason());
                    return;
                }
                quantity.getValueFactory().setValue(suggestion.quantity());
                onStatus.accept("수량을 " + suggestion.quantity() + "주로 맞췄습니다.");
            });
            buttons.add(button);
        }
        return buttons;
    }

    /**
     * 검토 단추.
     *
     * <p>누르면 잠깐 잠근다. 같은 주문이 연달아 나가는 것을 막는 설정이 켜져 있을 때만이다.
     * 되돌릴 수 없는 동작이라 손이 미끄러진 것과 두 번 내려는 것을 구별할 방법이 없다.
     */
    private Button previewButton() {
        Button preview = new Button("주문 내용 검토");
        preview.getStyleClass().add("primary-button");
        preview.setDefaultButton(true);
        preview.setAccessibleHelp("주문을 제출하지 않고 재확인 창을 엽니다.");
        preview.setOnAction(event -> {
            if (preventDuplicates) {
                preview.setDisable(true);
                PauseTransition unlock = new PauseTransition(Duration.millis(900));
                unlock.setOnFinished(done -> preview.setDisable(false));
                unlock.play();
            }
            onPreview.accept(viewModel.draft());
        });
        return preview;
    }

    /**
     * 고칠 수 없는 칸.
     *
     * <p>읽기 전용이어도 초점은 받는다. 이름이 없으면 스크린리더는 "편집" 이라고만 읽고,
     * 사용자는 무엇을 주문하려는지 모른 채 지나간다.
     */
    private static TextField readOnlyField(String label, String value) {
        TextField field = new TextField(value);
        field.setEditable(false);
        field.setAccessibleText(label + " " + value);
        field.setAccessibleHelp("종목을 바꾸려면 종목검색에서 다른 종목을 선택해주세요.");
        return field;
    }

    private static ComboBox<OrderSide> sideBox(OrderSide value) {
        ComboBox<OrderSide> box = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(OrderSide.values()));
        box.setValue(value);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(OrderSide side) {
                return side == null ? "" : side.displayName();
            }

            @Override
            public OrderSide fromString(String text) {
                return OrderSide.valueOf(text);
            }
        });
        return box;
    }

    private static ComboBox<OrderType> typeBox(OrderType value) {
        ComboBox<OrderType> box = new ComboBox<>(
                javafx.collections.FXCollections.observableArrayList(OrderType.values()));
        box.setValue(value);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(OrderType type) {
                return type == null ? "" : type.displayName();
            }

            @Override
            public OrderType fromString(String text) {
                return OrderType.valueOf(text);
            }
        });
        return box;
    }

    private static void field(GridPane grid, int column, int row, String label, Control control) {
        Label caption = new Label(label);
        caption.setLabelFor(control);
        control.setMaxWidth(Double.MAX_VALUE);
        grid.add(caption, column, row);
        grid.add(control, column + 1, row);
        GridPane.setHgrow(control, Priority.ALWAYS);
    }
}
