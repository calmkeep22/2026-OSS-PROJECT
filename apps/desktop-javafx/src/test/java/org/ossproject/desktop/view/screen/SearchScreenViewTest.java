package org.ossproject.desktop.view.screen;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.application.usecase.MarketApplicationService;
import org.ossproject.desktop.viewmodel.DesktopSession;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.desktop.viewmodel.StockSearchItem;
import org.ossproject.desktop.viewmodel.StockSearchViewModel;
import org.ossproject.fake.FakeCandleQueryAdapter;
import org.ossproject.fake.FakeMarketDataStreamAdapter;
import org.ossproject.fake.FakeStockQueryAdapter;
import org.ossproject.finance.model.market.SecuritySummary;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 목록을 방향키로 훑을 때 무엇이 선택됐는지 소리로 알려 준다.
 *
 * <p>행마다 accessibleText 는 붙어 있었지만 그것은 바깥 스크린리더의 몫이다. 이 앱의
 * 화면 읽기를 쓰는 사용자에게는 위아래를 눌러도 아무 소리가 나지 않아, 지금 어느
 * 종목에 서 있는지 알 수 없었다.
 */
@ExtendWith(JavaFxToolkit.class)
class SearchScreenViewTest {

    private static StockSearchItem item(String symbol, String name) {
        return new StockSearchItem(
                SecuritySummary.withoutQuote(symbol, name, "국내", "KRX", "KRW"));
    }

    private static StockSearchViewModel viewModel() {
        return new StockSearchViewModel(
                new DesktopSession(),
                new MarketApplicationService(new FakeStockQueryAdapter(),
                        new FakeCandleQueryAdapter(), new FakeMarketDataStreamAdapter(),
                        Runnable::run),
                Runnable::run);
    }

    private static javafx.scene.control.TextField findField(Node node) {
        if (node instanceof javafx.scene.control.TextField field) return field;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            return findField(scroll.getContent());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.TextField found = findField(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TableView<?> findTable(Node node) {
        if (node instanceof TableView<?> table) return table;
        if (node instanceof ScrollPane scroll && scroll.getContent() != null) {
            return findTable(scroll.getContent());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TableView<?> found = findTable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 못 찾았을 때 아무 말도 없으면, 화면을 볼 수 없는 사용자는 조회가 돌긴 한 것인지조차
     * 알 수 없다. 게다가 무엇으로 찾았는지도 되읽어 줘야 한다 — 한글 조합이 끼면 친 것과
     * 들어간 것이 다를 수 있다.
     */
    /**
     * 화면이 만들어질 때 한 번 도는 초기 갱신까지 "검색 결과가 없습니다" 라고 말했다.
     * 검색한 적도 없는 사용자가 그 말을 들었다 — 앱을 켜고 주문 화면으로 갔을 뿐인데.
     */
    @Test
    @DisplayName("검색하지 않았으면 결과 없음을 말하지 않는다")
    void staysQuietUntilSomethingIsSearched() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> spoken = new ArrayList<>();
            SearchScreenView view = new SearchScreenView(
                    viewModel(), screen -> { }, text -> { }, spoken::add, spoken::add);

            view.create();

            assertTrue(spoken.isEmpty(), "검색하기 전에 말하면 안 됩니다. 실제: " + spoken);
        });
    }

    @Test
    @DisplayName("결과가 없으면 검색어를 되읽어 주며 알린다")
    void speaksWhenNothingIsFound() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> spoken = new ArrayList<>();
            StockSearchViewModel viewModel = viewModel();
            SearchScreenView view = new SearchScreenView(
                    viewModel, screen -> { }, text -> { }, spoken::add);
            Node root = view.create();
            javafx.scene.control.TextField field = findField(root);
            assertNotNull(field, "검색어 칸을 찾지 못했습니다.");

            // 사용자가 실제로 하는 것과 같은 길로 간다 — 검색칸에 치고 Enter.
            field.setText("없는종목이름");
            field.fireEvent(new javafx.event.ActionEvent());

            assertTrue(spoken.stream().anyMatch(text -> text.contains("검색 결과가 없습니다")),
                    "결과 없음을 알리지 않았습니다. 실제: " + spoken);
            assertTrue(spoken.stream().anyMatch(text -> text.contains("없는종목이름")),
                    "무엇으로 찾았는지 되읽어 주지 않았습니다. 실제: " + spoken);
        });
    }

    @Test
    @DisplayName("고른 종목의 이름과 몇 번째인지 읽어 준다")
    void speaksTheHighlightedStock() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> spoken = new ArrayList<>();
            StockSearchViewModel viewModel = viewModel();
            viewModel.items().setAll(
                    item("005930", "삼성전자"), item("005935", "삼성전자우"),
                    item("000660", "SK하이닉스"));

            SearchScreenView view = new SearchScreenView(
                    viewModel, screen -> { }, text -> { }, spoken::add);
            Node root = view.create();
            TableView<?> table = findTable(root);
            assertNotNull(table, "검색 결과 표를 찾지 못했습니다.");

            table.getSelectionModel().select(1);

            assertTrue(spoken.contains("삼성전자우, 005935, 3건 중 2번째"),
                    "고른 종목을 읽어 주지 않았습니다. 실제: " + spoken);
        });
    }

    /**
     * 방향키를 빠르게 누르면 지나온 항목이 줄줄이 쌓인다. 손은 멈췄는데 소리가 한참
     * 뒤처져 따라오면 목록을 훑을 수가 없다. 옮길 때마다 한 번씩만 내보내고, 뒤에서
     * 밀린 것을 버리는 일은 음성 대기열이 맡는다.
     */
    @Test
    @DisplayName("한 번 옮길 때 한 번만 읽어 준다")
    void speaksOncePerMove() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> spoken = new ArrayList<>();
            StockSearchViewModel viewModel = viewModel();
            viewModel.items().setAll(
                    item("005930", "삼성전자"), item("005935", "삼성전자우"),
                    item("000660", "SK하이닉스"));

            SearchScreenView view = new SearchScreenView(
                    viewModel, screen -> { }, text -> { }, spoken::add);
            TableView<?> table = findTable(view.create());
            assertNotNull(table);

            table.getSelectionModel().select(0);
            table.getSelectionModel().select(1);
            table.getSelectionModel().select(2);

            assertEquals(3, spoken.size(), "옮긴 횟수만큼만 읽어야 합니다. 실제: " + spoken);
            assertTrue(spoken.get(2).startsWith("SK하이닉스"), spoken.toString());
        });
    }
}
