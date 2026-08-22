package org.ossproject.desktop.view;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ossproject.desktop.state.WatchlistItem;
import org.ossproject.desktop.testsupport.JavaFxToolkit;

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

    /** 담고 빼는 일이 곧바로 끝나는 단추. 대부분의 검사가 이것을 쓴다. */
    private static Button toggle(ObservableList<WatchlistItem> watchlist) {
        return new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                () -> watchlist.add(item("005930", "KRX")),
                () -> watchlist.removeIf(entry -> entry.symbol().equals("005930"))).button();
    }

    /** 담고 빼도 아무 일도 일어나지 않는 단추. 목록을 밖에서 바꿔 볼 때 쓴다. */
    private static Button inertToggle(ObservableList<WatchlistItem> watchlist) {
        return new WatchlistToggle(watchlist, "005930", "KRX", "삼성전자",
                () -> { }, () -> { }).button();
    }

    @Test
    @DisplayName("담기 전에는 추가, 담고 나면 취소로 바뀐다")
    void flipsAfterAdding() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = toggle(watchlist);

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
            Button button = toggle(watchlist);

            assertEquals("관심종목 취소", button.getText());
            button.fire();
            assertTrue(watchlist.isEmpty());
            assertEquals("관심종목 추가", button.getText());
        });
    }

    /**
     * 담았다 뺀 종목을 다시 담을 수 있어야 한다.
     *
     * <p>실제로 안 됐다. 단추가 담기의 결과를 기다렸는데 그 결과가 같은 화면 스레드에서
     * 도착하는 바람에, 화면 스레드가 자기가 실행할 일을 기다리다 굳었다. 5초 뒤 시간
     * 초과로 풀리고 담기는 실패로 끝났다.
     */
    @Test
    @DisplayName("담았다 빼고 다시 담을 수 있다")
    void canAddAgainAfterRemoving() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = toggle(watchlist);

            button.fire();
            assertEquals("관심종목 취소", button.getText());
            button.fire();
            assertEquals("관심종목 추가", button.getText());
            button.fire();

            assertEquals(1, watchlist.size(), "다시 담기지 않았습니다.");
            assertEquals("관심종목 취소", button.getText());
        });
    }

    /**
     * 담기가 곧바로 끝나지 않아도 된다.
     *
     * <p>종목 식별 정보를 조회해야 하는 경우가 있다. 단추가 그것을 기다리면 화면이 멈춘다.
     * 기다리지 않고 목록이 바뀌는 것을 본다.
     */
    @Test
    @DisplayName("담기가 늦게 끝나도 목록이 바뀌면 따라간다")
    void followsALateAdd() {
        JavaFxToolkit.onFxThread(() -> {
            ObservableList<WatchlistItem> watchlist = emptyList();
            Button button = inertToggle(watchlist);

            button.fire();
            assertEquals("관심종목 추가", button.getText(),
                    "아직 담기지 않았으므로 그대로여야 합니다.");

            // 조회가 끝나 뒤늦게 목록에 들어온다.
            watchlist.add(item("005930", "KRX"));
            assertEquals("관심종목 취소", button.getText());
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
            Button button = toggle(watchlist);

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
            Button button = inertToggle(watchlist);

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
            Button button = inertToggle(watchlist);

            assertEquals("관심종목 추가", button.getText());
        });
    }
}
