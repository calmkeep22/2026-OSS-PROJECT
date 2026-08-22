package org.ossproject.desktop.view;

import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import org.ossproject.desktop.state.WatchlistItem;

import java.util.Objects;

/**
 * 관심종목에 담고 빼는 단추.
 *
 * <p>담긴 뒤에도 "추가" 라고 적혀 있으면 사용자는 눌린 것인지 아닌지 알 수 없다. 눈으로
 * 보면 목록을 열어 확인할 수 있지만, 화면을 볼 수 없으면 확인할 방법이 상태 안내 문구
 * 한 줄뿐이고 그건 곧 사라진다. 단추 자신이 지금 상태를 들고 있어야 한다.
 *
 * <p>목록을 지켜본다. 관심종목 화면에서 지웠는데 이 단추가 "취소" 로 남아 있으면, 그
 * 단추는 거짓말을 하는 것이다.
 *
 * <p>담기가 끝났는지를 기다리지 않는다. 종목 식별 정보를 조회해야 하는 경우가 있어
 * 곧바로 끝나지 않는데, 여기서 기다리면 화면 스레드가 멈춘다. 실제로 그렇게 만들었다가
 * 조회 결과가 같은 화면 스레드에서 도착하는 바람에 서로를 기다려 굳었다.
 *
 * <p>대신 목록을 지켜보는 것으로 충분하다. 담기가 끝나면 목록이 바뀌고 단추가 따라간다.
 */
public final class WatchlistToggle {

    private static final String ADD = "관심종목 추가";
    private static final String REMOVE = "관심종목 취소";

    private final Button button = new Button();
    private final ObservableList<WatchlistItem> watchlist;
    private final String symbol;
    private final String exchange;
    private final String name;

    /**
     * @param exchange 거래소. 모르면 빈 문자열. 코스피와 나스닥에 같은 코드가 있을 수 있어
     *                 알 수 있으면 넘기는 편이 낫다
     * @param onAdd    담기. 곧바로 끝나지 않아도 된다. 끝나면 목록이 바뀌고 단추가 따라간다
     * @param onRemove 빼기
     */
    public WatchlistToggle(ObservableList<WatchlistItem> watchlist, String symbol, String exchange,
                           String name, Runnable onAdd, Runnable onRemove) {
        this.watchlist = Objects.requireNonNull(watchlist, "watchlist");
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.exchange = exchange == null ? "" : exchange;
        this.name = name == null || name.isBlank() ? symbol : name;
        Objects.requireNonNull(onAdd, "onAdd");
        Objects.requireNonNull(onRemove, "onRemove");

        button.getStyleClass().add("watchlist-toggle");
        button.setOnAction(event -> {
            // 결과를 기다리지 않는다. 목록이 바뀌면 아래 지켜보기가 단추를 고친다.
            if (contains()) {
                onRemove.run();
            } else {
                onAdd.run();
            }
            refresh();
        });
        // 다른 화면에서 지워도 따라간다. 안 그러면 이 단추만 지난 상태로 남는다.
        watchlist.addListener((ListChangeListener<WatchlistItem>) change -> refresh());
        refresh();
    }

    public Button button() {
        return button;
    }

    private boolean contains() {
        return watchlist.stream().anyMatch(item -> item.symbol().equalsIgnoreCase(symbol)
                && (exchange.isBlank() || item.exchange().equalsIgnoreCase(exchange)));
    }

    /**
     * 지금 상태를 단추에 적는다.
     *
     * <p>글자만 바꾸지 않고 접근 가능한 이름도 함께 바꾼다. 스크린리더는 글자를 다시 읽지
     * 않는 경우가 있어, 이름이 그대로면 사용자는 바뀐 것을 모른다.
     */
    private void refresh() {
        boolean inList = contains();
        button.setText(inList ? REMOVE : ADD);
        button.setAccessibleText(inList
                ? name + " 관심종목 취소, 지금 담겨 있음"
                : name + " 관심종목 추가, 지금 담겨 있지 않음");
    }
}
