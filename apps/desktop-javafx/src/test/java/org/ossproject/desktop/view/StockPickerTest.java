package org.ossproject.desktop.view;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.testsupport.JavaFxToolkit;
import org.ossproject.desktop.viewmodel.StockSelection;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 종목 하나 바꾸는 데 화면을 두 번 옮겨야 했다. 그리고 화면에 고르개가 없으면 지금 어느
 * 종목을 보고 있는지 알 방법이 제목뿐인데, 스크린리더로는 처음 한 번 읽히고 지나간다.
 */
@ExtendWith(JavaFxToolkit.class)
class StockPickerTest {

    private static StockSelection stock(String symbol, String name) {
        return new StockSelection("국내", symbol, name, "KRX", "KRW");
    }

    private static WatchlistItem watched(String symbol, String name) {
        return new WatchlistItem("국내", "국내", symbol, name, "KRX", "KRW", "없음");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Object> boxOf(Node root) {
        if (root instanceof ComboBox<?> box) {
            return (ComboBox<Object>) box;
        }
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ComboBox<Object> found = boxOf(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> labelsOf(StockPicker picker) {
        List<String> labels = new ArrayList<>();
        ComboBox<Object> box = boxOf(picker.root());
        for (Object entry : box.getItems()) {
            labels.add(box.getConverter().toString(entry));
        }
        return labels;
    }

    /** 돈이 들어가 있는 쪽이 먼저 읽혀야 한다. */
    @Test
    @DisplayName("보유 종목을 관심 종목보다 먼저 놓는다")
    void putsHoldingsFirst() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(watched("035720", "카카오"));
            StockPicker picker = new StockPicker(watchlist, stock("005930", "삼성전자"),
                    selected -> { });
            picker.setHoldings(List.of(stock("000660", "SK하이닉스")));

            List<String> labels = labelsOf(picker);
            assertTrue(labels.indexOf("보유 · SK하이닉스") < labels.indexOf("관심 · 카카오"),
                    labels.toString());
        });
    }

    /** 검색으로 찾아 들어온 종목은 어느 목록에도 없다. 빼면 고르개가 화면과 어긋난다. */
    @Test
    @DisplayName("지금 보는 종목이 목록에 없어도 남긴다")
    void keepsTheStockBeingViewed() {
        JavaFxToolkit.onFxThread(() -> {
            StockPicker picker = new StockPicker(FXCollections.observableArrayList(),
                    stock("005930", "삼성전자"), selected -> { });

            assertTrue(labelsOf(picker).contains("보는 중 · 삼성전자"), labelsOf(picker).toString());
        });
    }

    /** 보유이면서 관심이기도 한 종목이 두 번 나오면 목록이 길어지고 헷갈린다. */
    @Test
    @DisplayName("양쪽에 있는 종목은 한 번만 넣는다")
    void listsEachStockOnce() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(watched("000660", "SK하이닉스"));
            StockPicker picker = new StockPicker(watchlist, stock("005930", "삼성전자"),
                    selected -> { });
            picker.setHoldings(List.of(stock("000660", "SK하이닉스")));

            assertEquals(1, labelsOf(picker).stream()
                    .filter(label -> label.endsWith("SK하이닉스")).count(), labelsOf(picker).toString());
        });
    }

    @Test
    @DisplayName("고르면 그 종목을 알린다")
    void reportsTheChosenStock() {
        JavaFxToolkit.onFxThread(() -> {
            List<StockSelection> chosen = new ArrayList<>();
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(watched("035720", "카카오"));
            StockPicker picker = new StockPicker(watchlist, stock("005930", "삼성전자"),
                    chosen::add);

            ComboBox<Object> box = boxOf(picker.root());
            box.getItems().stream()
                    .filter(entry -> box.getConverter().toString(entry).endsWith("카카오"))
                    .findFirst().ifPresent(box::setValue);

            assertEquals(1, chosen.size());
            assertEquals("035720", chosen.get(0).symbol());
        });
    }

    /**
     * 계좌 조회가 끝나 보유 종목이 채워질 때 고르개가 다른 종목을 가리키면, 사용자는
     * 자기가 고르지도 않은 종목의 분석을 보게 된다.
     */
    @Test
    @DisplayName("보유 종목이 나중에 채워져도 고른 종목은 그대로다")
    void keepsTheSelectionWhenHoldingsArrive() {
        JavaFxToolkit.onFxThread(() -> {
            List<StockSelection> chosen = new ArrayList<>();
            StockPicker picker = new StockPicker(FXCollections.observableArrayList(),
                    stock("005930", "삼성전자"), chosen::add);

            picker.setHoldings(List.of(stock("000660", "SK하이닉스")));

            ComboBox<Object> box = boxOf(picker.root());
            assertTrue(box.getConverter().toString(box.getValue()).endsWith("삼성전자"),
                    box.getConverter().toString(box.getValue()));
            assertTrue(chosen.isEmpty(), "목록을 채우는 것만으로 종목이 바뀌면 안 됩니다.");
        });
    }

    /** 방금 담은 종목이 목록에 없으면 다시 검색해야 한다. */
    @Test
    @DisplayName("관심 목록이 바뀌면 따라간다")
    void followsTheWatchlist() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist = FXCollections.observableArrayList();
            StockPicker picker = new StockPicker(watchlist, stock("005930", "삼성전자"),
                    selected -> { });

            watchlist.add(watched("035720", "카카오"));

            assertTrue(labelsOf(picker).contains("관심 · 카카오"), labelsOf(picker).toString());
        });
    }

    /** 스크린리더는 고르개 이름만 듣는다. 몇 개 중 무엇인지가 거기 없으면 알 수 없다. */
    @Test
    @DisplayName("고르개 이름이 현재 종목과 개수를 함께 말한다")
    void accessibleNameCarriesTheState() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(watched("035720", "카카오"));
            StockPicker picker = new StockPicker(watchlist, stock("005930", "삼성전자"),
                    selected -> { });

            String name = boxOf(picker.root()).getAccessibleText();
            assertTrue(name.contains("삼성전자"), name);
            assertTrue(name.contains("2개"), name);
        });
    }
}
