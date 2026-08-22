package org.ossproject.desktop.orderbook;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.finance.model.orderbook.PriceLadderRow;
import org.ossproject.finance.model.orderbook.PriceLadderView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
}
