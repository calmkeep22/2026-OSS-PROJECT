package org.ossproject.desktop.orderbook;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.finance.model.orderbook.PriceLadderRow;
import org.ossproject.finance.model.orderbook.PriceLadderView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 호가는 한눈에 보아야 판단이 된다. 표 안에서 스크롤하게 두면 위아래 호가를 함께 볼 수
 * 없고, 스크린리더로 읽을 때도 보이지 않는 행을 지나치기 쉽다.
 *
 * <p>표를 행 수에 맞춰 키워 두었는데도 주문 화면에서 잘렸다. 그 화면에서는 호가 칸이
 * {@link SplitPane} 안에 들어가는데, SplitPane 은 자식의 pref 를 무시하고 제 기본
 * 높이를 쓴다. 그래서 최소 높이로 걸어야 한다.
 */
@ExtendWith(JavaFxToolkit.class)
class OrderBookLadderHeightTest {

    private static PriceLadderView ladderWith(int levels) {
        List<PriceLadderRow> rows = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            BigDecimal price = BigDecimal.valueOf(666000L - i * 1000L);
            rows.add(new PriceLadderRow(price,
                    i < levels / 2 ? 1000L : 0L, i < levels / 2 ? 0L : 1000L,
                    0L, 0L, 0.5, 0.5, i == levels / 2));
        }
        return new PriceLadderView("000810", List.copyOf(rows), 1000L, false, "",
                java.time.Instant.EPOCH);
    }

    /** 단계가 늘면 필요한 높이도 늘어야 한다. 고정값이면 아래 단계가 잘린다. */
    @Test
    @DisplayName("필요 높이가 호가 단계 수를 따라간다")
    void requiredHeightFollowsTheLevelCount() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView view = new OrderBookLadderView("삼성화재");

            view.update(ladderWith(5));
            double small = view.requiredHeight().get();
            view.update(ladderWith(21));
            double large = view.requiredHeight().get();

            assertTrue(large > small, small + " → " + large);
        });
    }

    /**
     * 주문 화면과 같은 구조로 세워 실제로 잘리는지 본다.
     *
     * <p>사다리 높이만 재면 통과하지만 화면에서는 잘렸다. SplitPane 을 거쳐야 드러난다.
     */
    @Test
    @DisplayName("SplitPane 안에서도 호가 표가 잘리지 않는다")
    void isNotClippedInsideASplitPane() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView ladder = new OrderBookLadderView("삼성화재");
            TabPane views = new TabPane(new Tab("호가 표", ladder.root()),
                    new Tab("누적 깊이 그래프", new Label("그래프")));
            views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            views.minHeightProperty().bind(ladder.requiredHeight().add(44));
            views.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

            // 주문 화면과 같은 구조다. 스크롤 안에서는 VBox 가 pref 높이로 잡히고,
            // SplitPane 의 pref 는 자식과 무관한 기본값이라 여기서 잘렸다.
            SplitPane orderArea = new SplitPane(views, new Label("주문 폼"));
            javafx.scene.layout.VBox body = new javafx.scene.layout.VBox(10, orderArea);
            javafx.scene.layout.VBox.setVgrow(orderArea, javafx.scene.layout.Priority.ALWAYS);
            javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(body);
            scroll.setFitToWidth(true);
            StackPane host = new StackPane(scroll);
            new Scene(host, 1400, 900);

            ladder.update(ladderWith(21));
            host.applyCss();
            host.layout();

            assertTrue(orderArea.getHeight() >= ladder.requiredHeight().get(),
                    "호가 칸이 필요한 높이보다 작습니다. 실제 " + orderArea.getHeight()
                            + ", 필요 " + ladder.requiredHeight().get());
        });
    }

    /**
     * 자리가 있으면 모든 단계가 한눈에 들어와야 한다. 기준 높이가 단계 수를 따라간다.
     */
    @Test
    @DisplayName("기준 높이는 모든 단계를 담을 만큼 커진다")
    void preferredHeightCoversEveryLevel() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView view = new OrderBookLadderView("삼성화재");

            view.update(ladderWith(5));
            double small = view.preferredHeight().get();
            view.update(ladderWith(21));
            double large = view.preferredHeight().get();

            assertTrue(large > small, small + " → " + large);
            // 최소는 쓸 만한 만큼만 요구한다. 여기까지 함께 커지면 짧은 창에서 잘린다.
            assertTrue(view.requiredHeight().get() < large,
                    "최소가 기준만큼 커지면 짧은 창을 넘친다. 최소 "
                            + view.requiredHeight().get() + ", 기준 " + large);
        });
    }

    /**
     * 큰 글자 모드에서 표가 창보다 길어졌을 때 맨 아래 단계가 스크롤도 없이 잘렸다.
     *
     * <p>글자를 키웠다고 호가가 사라지면 안 된다. 창이 짧으면 표는 창 안에 들어오고,
     * 못 보여 준 단계는 값에 그대로 남아 스크롤과 방향키로 닿을 수 있어야 한다.
     */
    @Test
    @DisplayName("창이 짧으면 잘리지 않고 표 안에서 스크롤된다")
    void scrollsInsteadOfClippingWhenTheWindowIsShort() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView ladder = new OrderBookLadderView("삼성화재");
            TabPane views = new TabPane(new Tab("호가 표", ladder.root()),
                    new Tab("누적 깊이 그래프", new Label("그래프")));
            views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            views.minHeightProperty().bind(ladder.requiredHeight().add(44));
            views.prefHeightProperty().bind(ladder.preferredHeight().add(44));
            views.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

            VBox body = new VBox(10, views);
            StackPane host = new StackPane(body);
            // 21 단계를 다 펼치기에는 턱없이 짧은 창이다.
            new Scene(host, 1200, 420);

            ladder.update(ladderWith(21));
            host.applyCss();
            host.layout();

            VBox root = (VBox) ladder.root();
            @SuppressWarnings("unchecked")
            TableView<PriceLadderRow> table = (TableView<PriceLadderRow>) root.getChildren().get(3);

            assertEquals(21, table.getItems().size(),
                    "자리가 없다고 단계를 버리면 안 됩니다. 스크롤로 닿아야 합니다");
            assertTrue(table.getHeight() <= host.getHeight(),
                    "표가 창을 넘치면 아래가 잘립니다. 표 " + table.getHeight()
                            + ", 창 " + host.getHeight());
        });
    }

    /** 호가를 못 받았을 때도 칸이 무너지면 안 된다. 안내 문구가 보여야 한다. */
    @Test
    @DisplayName("호가를 기다리는 동안에도 높이가 0 이 아니다")
    void keepsRoomWhileWaiting() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView ladder = new OrderBookLadderView("삼성화재");
            ladder.showUnavailable("호가를 기다리고 있습니다.");

            assertTrue(ladder.requiredHeight().get() > 0);
        });
    }

    @Test
    @DisplayName("선택한 호가를 Enter 키로 주문 화면에 전달한다")
    void forwardsSelectedPriceForAnOrder() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView ladder = new OrderBookLadderView("삼성화재");
            PriceLadderView values = ladderWith(5);
            AtomicReference<BigDecimal> selectedPrice = new AtomicReference<>();
            ladder.setOnPriceSelected(selectedPrice::set);
            ladder.update(values);

            VBox root = (VBox) ladder.root();
            @SuppressWarnings("unchecked")
            TableView<PriceLadderRow> table = (TableView<PriceLadderRow>) root.getChildren().get(3);
            table.getSelectionModel().select(2);
            table.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, false, false, false));

            assertEquals(values.rows().get(2).price(), selectedPrice.get());
        });
    }

    @Test
    @DisplayName("호가가 도착하면 키보드 시작점은 현재가 행이다")
    void startsKeyboardNavigationAtTheCurrentPrice() {
        JavaFxToolkit.onFxThread(() -> {
            OrderBookLadderView ladder = new OrderBookLadderView("삼성화재");
            PriceLadderView values = ladderWith(5);
            ladder.update(values);

            VBox root = (VBox) ladder.root();
            @SuppressWarnings("unchecked")
            TableView<PriceLadderRow> table = (TableView<PriceLadderRow>) root.getChildren().get(3);
            assertEquals(values.currentPriceRow().orElseThrow(),
                    table.getSelectionModel().getSelectedItem());
        });
    }
}
