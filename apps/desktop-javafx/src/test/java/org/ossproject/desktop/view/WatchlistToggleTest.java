package org.ossproject.desktop.view;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 담긴 뒤에도 "추가" 라고 적혀 있으면 사용자는 눌린 것인지 알 수 없다. 눈으로 보면 목록을
 * 열어 확인할 수 있지만, 화면을 볼 수 없으면 확인할 길이 상태 안내 한 줄뿐이고 그건 곧
 * 사라진다.
 */
@ExtendWith(JavaFxToolkit.class)
class WatchlistToggleTest {

    private static WatchlistItem item(String symbol, String exchange) {
        return new WatchlistItem("국내", "국내", symbol, symbol + " 종목", exchange, "KRW", "없음");
    }

    private static ObservableList<WatchlistItem> emptyList() {
        return FXCollections.observableArrayList();
    }

    @Test
    @DisplayName("담기 전에는 추가, 담고 나면 취소로 바뀐다")
    void flipsAfterAdding() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> watchlist.add(item("005930", "KRX")),
                    () -> watchlist.removeIf(entry -> entry.symbol().equals("005930")),
                    text -> { }).button();

            assertEquals("관심종목 추가", button.getText());
            button.fire();
            assertEquals("관심종목 취소", button.getText());
        });
    }

    @Test
    @DisplayName("취소를 누르면 목록에서 빠지고 다시 추가로 돌아온다")
    void flipsBackAfterRemoving() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(item("005930", "KRX"));
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> watchlist.add(item("005930", "KRX")),
                    () -> watchlist.removeIf(entry -> entry.symbol().equals("005930")),
                    text -> { }).button();

            assertEquals("관심종목 취소", button.getText());
            button.fire();
            assertTrue(watchlist.isEmpty());
            assertEquals("관심종목 추가", button.getText());
        });
    }

    /**
     * 스크린리더는 글자가 바뀌어도 다시 읽지 않는 경우가 있다. 접근 가능한 이름이 그대로면
     * 사용자는 바뀐 것을 모른다.
     */
    @Test
    @DisplayName("접근 가능한 이름도 상태를 함께 말한다")
    void accessibleNameCarriesTheState() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> watchlist.add(item("005930", "KRX")),
                    () -> watchlist.clear(), text -> { }).button();

            assertTrue(button.getAccessibleText().contains("담겨 있지 않음"),
                    button.getAccessibleText());
            button.fire();
            assertTrue(button.getAccessibleText().contains("담겨 있음"), button.getAccessibleText());
        });
    }

    /** 관심종목 화면에서 지웠는데 이 단추가 "취소" 로 남아 있으면 거짓말을 하는 것이다. */
    @Test
    @DisplayName("다른 화면에서 지워도 따라 바뀐다")
    void followsChangesMadeElsewhere() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(item("005930", "KRX"));
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> true, () -> { }, text -> { }).button();

            assertEquals("관심종목 취소", button.getText());
            watchlist.clear();
            assertEquals("관심종목 추가", button.getText());
        });
    }

    /** 코스피와 나스닥에 같은 코드가 있을 수 있다. 한쪽을 담았는데 다른 쪽이 담겨 보이면 안 된다. */
    @Test
    @DisplayName("코드가 같아도 거래소가 다르면 담긴 것으로 보지 않는다")
    void doesNotConfuseTheSameCodeOnAnotherExchange() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist =
                    FXCollections.observableArrayList(item("005930", "NASDAQ"));
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> true, () -> { }, text -> { }).button();

            assertEquals("관심종목 추가", button.getText());
        });
    }

    /** 담기가 거절될 수 있다. 목록이 그대로면 단추도 그대로여야 한다. */
    @Test
    @DisplayName("담기가 실패하면 취소로 바뀌지 않는다")
    void staysPutWhenAddingFails() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> said = new ArrayList<>();
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> false, () -> { }, said::add).button();

            button.fire();

            assertEquals("관심종목 추가", button.getText());
            assertTrue(said.get(0).contains("담지 못했습니다"), said.toString());
        });
    }

    /** 상태 줄에도 알린다. 단추 글자를 못 본 사용자에게는 이쪽이 먼저 들린다. */
    @Test
    @DisplayName("담고 뺄 때 상태 줄에도 알린다")
    void announcesBothWays() {
        JavaFxToolkit.onFxThread(() -> {
            List<String> said = new ArrayList<>();
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                    () -> watchlist.add(item("005930", "KRX")),
                    () -> watchlist.clear(), said::add).button();

            button.fire();
            button.fire();

            assertTrue(said.get(0).contains("담았습니다"), said.toString());
            assertTrue(said.get(1).contains("뺐습니다"), said.toString());
        });
    }
}
