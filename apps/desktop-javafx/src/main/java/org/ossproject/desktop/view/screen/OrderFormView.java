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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
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
import java.util.function.Supplier;

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
    private final PriceShortcuts priceShortcuts;
    /** 같은 주문을 연달아 내지 못하게 막을지. 설정에서 온다. */
    private final boolean preventDuplicates;
    private TextField priceField;
    private ComboBox<OrderType> orderTypeField;

    /** 호가가 아직 도착하지 않았을 수 있으므로 모든 값은 Optional 로 받는다. */
    public record PriceShortcuts(Supplier<java.util.Optional<BigDecimal>> currentPrice,
                                 Supplier<java.util.Optional<BigDecimal>> bestAsk,
                                 Supplier<java.util.Optional<BigDecimal>> bestBid,
                                 Supplier<java.util.Optional<BigDecimal>> tickSize) {
        public PriceShortcuts {
            Objects.requireNonNull(currentPrice, "currentPrice");
            Objects.requireNonNull(bestAsk, "bestAsk");
            Objects.requireNonNull(bestBid, "bestBid");
            Objects.requireNonNull(tickSize, "tickSize");
        }

        public static PriceShortcuts unavailable() {
            Supplier<java.util.Optional<BigDecimal>> empty = java.util.Optional::empty;
            return new PriceShortcuts(empty, empty, empty, empty);
        }
    }

    public OrderFormView(OrderDraftViewModel viewModel, boolean preventDuplicates,
                         Function<BigDecimal, String> formatMoney,
                         Consumer<OrderDraft> onPreview, Consumer<String> onStatus,
                         Consumer<OrderDraft> onDraftChanged) {
        this(viewModel, preventDuplicates, formatMoney, onPreview, onStatus, onDraftChanged,
                PriceShortcuts.unavailable());
    }

    public OrderFormView(OrderDraftViewModel viewModel, boolean preventDuplicates,
                         Function<BigDecimal, String> formatMoney,
                         Consumer<OrderDraft> onPreview, Consumer<String> onStatus,
                         Consumer<OrderDraft> onDraftChanged, PriceShortcuts priceShortcuts) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.preventDuplicates = preventDuplicates;
        this.formatMoney = Objects.requireNonNull(formatMoney, "formatMoney");
        this.onPreview = Objects.requireNonNull(onPreview, "onPreview");
        this.onStatus = Objects.requireNonNull(onStatus, "onStatus");
        this.onDraftChanged = Objects.requireNonNull(onDraftChanged, "onDraftChanged");
        this.priceShortcuts = Objects.requireNonNull(priceShortcuts, "priceShortcuts");
    }

    public VBox create() {
        OrderDraft draft = viewModel.draft();

        TextField symbol = readOnlyField("종목 코드", draft.symbol());
        TextField name = readOnlyField("종목명", draft.name());
        ComboBox<OrderSide> side = sideBox(draft.side());
        side.setId("order-side-field");
        side.setAccessibleText("매수 또는 매도");
        ComboBox<OrderType> orderType = typeBox(draft.type());
        orderType.setId("order-type-field");
        orderType.setAccessibleText("주문 유형");
        Spinner<Integer> quantity = new Spinner<>(1, 1_000_000, draft.quantity());
        quantity.setId("order-quantity-field");
        quantity.setEditable(true);
        quantity.setAccessibleText("주문 수량");
        // 스피너에 이름을 달아도 초점은 안쪽 편집기가 받는다. 편집기에 이름이 없으면
        // 스크린리더는 "편집" 이라고만 읽는다.
        quantity.getEditor().setAccessibleText("주문 수량");
        TextField price = new TextField(draft.price());
        price.setId("order-price-field");
        price.setAccessibleText("주문 가격");
        price.setDisable(draft.type() == OrderType.MARKET);
        priceField = price;
        orderTypeField = orderType;

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
        Label draftSummary = new Label();
        draftSummary.setWrapText(true);
        draftSummary.getStyleClass().add("order-draft-summary");
        Label shortcutGuide = new Label("단축키  Alt+B 매수 · Alt+Shift+B 매도 · Alt+P 가격 · "
                + "Alt+N 수량 · Ctrl+Enter 주문 검토");
        shortcutGuide.setWrapText(true);
        shortcutGuide.getStyleClass().add("muted-label");
        shortcutGuide.setAccessibleText("주문 단축키 안내. Alt B 매수, Alt Shift B 매도, Alt P 가격, "
                + "Alt N 수량, Control Enter 주문 검토.");
        Runnable refresh = () -> {
            viewModel.update(side.getValue(), orderType.getValue(), quantity.getValue(),
                    price.getText());
            estimated.setText(viewModel.estimatedAmount(price.getText(), quantity.getValue())
                    .map(formatMoney).orElse("가격을 확인하세요"));
            String priceText = orderType.getValue() == OrderType.MARKET ? "시장가"
                    : parsePrice(price.getText()).map(formatMoney).orElse("가격 확인 필요");
            draftSummary.setText(name.getText() + " " + side.getValue().displayName() + ", "
                    + orderType.getValue().displayName() + " " + priceText + ", "
                    + quantity.getValue() + "주.");
            draftSummary.setAccessibleText("현재 주문 초안. " + draftSummary.getText());
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

        FlowPane priceRow = priceShortcutRow(price, orderType);
        FlowPane quantityRow = quantityShortcutRow(quantity);
        List<Button> ratioButtons = ratioButtons(side, orderType, price, quantity);
        FlowPane ratioRow = wrappingRow(8, ratioButtons.toArray(Button[]::new));
        ratioRow.getChildren().add(0, new Label("주문 비율"));

        VBox estimates = new VBox(4,
                informationRow("주문 예상금액", estimated),
                informationRow("주문 가능금액", orderable));
        estimates.getStyleClass().add("estimate-box");
        estimates.setPadding(new Insets(8));

        Button preview = previewButton();
        VBox box = new VBox(8, sectionHeading("모의주문 준비"), form, priceRow, quantityRow,
                ratioRow, draftSummary, shortcutGuide, estimates, preview);
        box.getStyleClass().addAll("panel-card", "order-form-compact");
        box.setPadding(new Insets(12));
        box.setMaxHeight(Double.MAX_VALUE);
        this.orderableLabel = orderable;
        this.ratios = ratioButtons;
        installKeyboardShortcuts(box, side, orderType, price, quantity, preview);
        // 첫 진입은 읽기 전용 종목 코드가 아니라 실제로 결정해야 하는 매수/매도에서 시작한다.
        box.sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene != null) javafx.application.Platform.runLater(side::requestFocus);
        });
        return box;
    }

    private FlowPane priceShortcutRow(TextField price, ComboBox<OrderType> orderType) {
        Button current = priceButton("현재가", "order-price-current", priceShortcuts.currentPrice(), price);
        Button ask = priceButton("매도 1호가", "order-price-best-ask", priceShortcuts.bestAsk(), price);
        Button bid = priceButton("매수 1호가", "order-price-best-bid", priceShortcuts.bestBid(), price);
        Button down = new Button("-1호가");
        Button up = new Button("+1호가");
        down.setId("order-price-down");
        up.setId("order-price-up");
        down.setOnAction(event -> movePrice(price, -1));
        up.setOnAction(event -> movePrice(price, 1));
        List<Button> buttons = List.of(current, ask, bid, down, up);
        Runnable enabled = () -> buttons.forEach(button ->
                button.setDisable(orderType.getValue() == OrderType.MARKET));
        orderType.valueProperty().addListener((observable, old, value) -> enabled.run());
        enabled.run();
        FlowPane row = wrappingRow(8, buttons.toArray(Button[]::new));
        row.getChildren().add(0, new Label("가격 빠른 선택"));
        return row;
    }

    private Button priceButton(String text, String id, Supplier<java.util.Optional<BigDecimal>> supplier,
                               TextField price) {
        Button button = new Button(text);
        button.setId(id);
        button.setAccessibleHelp(text + "를 지정가 입력칸에 넣습니다.");
        button.setOnAction(event -> supplier.get().ifPresentOrElse(value -> {
            selectLimitPrice(value);
            onStatus.accept(text + " " + formatMoney.apply(value) + "을 지정가로 넣었습니다.");
        }, () -> onStatus.accept("호가를 아직 받지 못했습니다. 잠시 후 다시 시도해주세요.")));
        return button;
    }

    private FlowPane quantityShortcutRow(Spinner<Integer> quantity) {
        Button minus = quantityButton("-1주", "order-quantity-down", quantity, -1, null);
        Button plus = quantityButton("+1주", "order-quantity-up", quantity, 1, null);
        Button one = quantityButton("1주", "order-quantity-1", quantity, 0, 1);
        Button five = quantityButton("5주", "order-quantity-5", quantity, 0, 5);
        Button ten = quantityButton("10주", "order-quantity-10", quantity, 0, 10);
        FlowPane row = wrappingRow(8, minus, plus, one, five, ten);
        row.getChildren().add(0, new Label("수량 빠른 선택"));
        return row;
    }

    private Button quantityButton(String text, String id, Spinner<Integer> quantity,
                                  int delta, Integer fixed) {
        Button button = new Button(text);
        button.setId(id);
        button.setOnAction(event -> {
            int next = fixed == null ? quantity.getValue() + delta : fixed;
            quantity.getValueFactory().setValue(Math.max(1, Math.min(1_000_000, next)));
            onStatus.accept("주문 수량을 " + quantity.getValue() + "주로 맞췄습니다.");
            quantity.requestFocus();
        });
        return button;
    }

    private void movePrice(TextField price, int direction) {
        java.util.Optional<BigDecimal> current = parsePrice(price.getText());
        java.util.Optional<BigDecimal> tick = priceShortcuts.tickSize().get();
        if (current.isEmpty() || tick.isEmpty() || tick.get().signum() <= 0) {
            onStatus.accept("호가 간격을 아직 알 수 없습니다. 호가표에서 가격을 선택해주세요.");
            return;
        }
        BigDecimal next = current.get().add(tick.get().multiply(BigDecimal.valueOf(direction)));
        if (next.signum() > 0) selectLimitPrice(next);
    }

    private void installKeyboardShortcuts(VBox root, ComboBox<OrderSide> side,
                                          ComboBox<OrderType> orderType, TextField price,
                                          Spinner<Integer> quantity, Button preview) {
        root.setAccessibleHelp("주문 단축키. Alt+B 매수, Alt+Shift+B 매도, Alt+P 가격, Alt+N 수량, "
                + "가격 칸 위아래 화살표 한 호가 조절, Control+Enter 주문 검토.");
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isAltDown() && !event.isShiftDown() && event.getCode() == KeyCode.B) {
                side.setValue(OrderSide.BUY); side.requestFocus(); event.consume();
            } else if (event.isAltDown() && event.isShiftDown() && event.getCode() == KeyCode.B) {
                side.setValue(OrderSide.SELL); side.requestFocus(); event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.P) {
                if (orderType.getValue() == OrderType.LIMIT) { price.requestFocus(); price.selectAll(); }
                else onStatus.accept("시장가 주문에는 가격을 입력하지 않습니다.");
                event.consume();
            } else if (event.isAltDown() && event.getCode() == KeyCode.N) {
                quantity.requestFocus(); quantity.getEditor().selectAll(); event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.ENTER) {
                preview.fire(); event.consume();
            } else if (event.getTarget() == price && event.getCode() == KeyCode.UP) {
                movePrice(price, 1); event.consume();
            } else if (event.getTarget() == price && event.getCode() == KeyCode.DOWN) {
                movePrice(price, -1); event.consume();
            }
        });
    }

    private static java.util.Optional<BigDecimal> parsePrice(String text) {
        try {
            BigDecimal value = new BigDecimal(text == null ? "" : text.replace(",", "").trim());
            return value.signum() > 0 ? java.util.Optional.of(value) : java.util.Optional.empty();
        } catch (NumberFormatException invalid) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 호가표에서 고른 가격을 지정가 입력칸에 반영한다.
     *
     * @return 지정가라 반영했으면 {@code true}, 시장가이거나 화면 준비 전이면 {@code false}
     */
    public boolean selectLimitPrice(BigDecimal selectedPrice) {
        if (selectedPrice == null || selectedPrice.signum() <= 0 || priceField == null
                || orderTypeField == null || orderTypeField.getValue() != OrderType.LIMIT) {
            return false;
        }
        priceField.setText(selectedPrice.stripTrailingZeros().toPlainString());
        priceField.requestFocus();
        return true;
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
     * <p>값은 접근성 이름으로 읽히되 탭 순서에서는 뺀다. 처음 초점은 매수/매도에 간다.
     */
    private static TextField readOnlyField(String label, String value) {
        TextField field = new TextField(value);
        field.setEditable(false);
        field.setAccessibleText(label + " " + value);
        field.setAccessibleHelp("종목을 바꾸려면 종목검색에서 다른 종목을 선택해주세요.");
        field.setFocusTraversable(false);
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
